package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.ui.LanRemoteViewModel
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 屏幕捕获与画面广播的前台服务 (ScreenCaptureService)
 * 启动系统投屏机制，获取实时屏幕数据并进行格式压缩，提供给共控端显示并进行反向控制
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "ScreenCaptureChannel"
        private const val NOTIFICATION_ID = 8821
        
        // 使用 StateFlow 实现运行状态的响应式暴露，这样可以在 Jetpack Compose UI 中完美响应
        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()
        
        /**
         * 记录后台流式屏幕截取服务是否正在运行的全局标识
         */
        val isRunning: Boolean
            get() = _isServiceRunning.value

        /**
         * 更新服务运行状态，能够保证 UI 框架立刻感知到变化
         */
        fun updateRunningState(running: Boolean) {
            _isServiceRunning.value = running
        }
    }

    // 系统多媒体投影管理器 (用于请求和管理屏幕录制)
    private var mediaProjectionManager: MediaProjectionManager? = null
    // 媒体投影连接会话
    private var mediaProjection: MediaProjection? = null
    // 系统虚拟显示器 (将屏幕物理像素投影到我们自定义的编码器 Surface 上)
    private var virtualDisplay: VirtualDisplay? = null
    
    // MediaCodec 硬件 AVC/H264 编码器
    private var mediaCodec: MediaCodec? = null
    private var codecSurface: Surface? = null
    private var codecThread: Thread? = null
    private var isEncoding = false

    /**
     * 服务初始化生命周期
     */
    override fun onCreate() {
        super.onCreate()
        updateRunningState(true)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    /**
     * 服务通过 startService 开始执行
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = createNotification()
        
        // 在 Android Q 及以上版本，前台服务必须明确声明 mediaProjection TYPE，否则会导致崩溃或启动失败
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // 获取来自 Activity 启动授权后传入的屏幕捕捉会话结果代码与授权信息
        val resultCode = intent?.getIntExtra("RESULT_CODE", android.app.Activity.RESULT_CANCELED) ?: android.app.Activity.RESULT_CANCELED
        val data = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra("DATA", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra<Intent>("DATA")
            }
        } catch (e: Throwable) {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra<Intent>("DATA")
        }

        // 验证系统的屏幕投射许可结果是否已经被正常授予
        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            Log.e(TAG, "Result code is not OK or data intent is null. Stopping service...")
            LanRemoteViewModel.instance?.addServerLog("投屏启动失败：未获得用户授权", com.example.ui.LogType.WARNING)
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            // 利用 MediaProjectionManager 与 resultCode/data 实例化屏幕流捕获连接句柄
            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)
            if (mediaProjection == null) {
                Log.e(TAG, "Failed to get MediaProjection instance")
                LanRemoteViewModel.instance?.addServerLog("投屏授权获取失败：MediaProjection projection is null", com.example.ui.LogType.WARNING)
                stopSelf()
                return START_NOT_STICKY
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException getting MediaProjection: ${e.message}")
            LanRemoteViewModel.instance?.addServerLog("系统投屏拒绝授权或已被占用: ${e.message}", com.example.ui.LogType.WARNING)
            stopSelf()
            return START_NOT_STICKY
        } catch (e: Throwable) {
            Log.e(TAG, "Error getting MediaProjection: ${e.message}")
            LanRemoteViewModel.instance?.addServerLog("创建投屏引擎异常: ${e.message}", com.example.ui.LogType.WARNING)
            stopSelf()
            return START_NOT_STICKY
        }

        setupScreenCapture()
        return START_STICKY
    }

    /**
     * 配置屏幕采集参数并注册底层帧监听，开启流畅的渲染循环机制
     */
    private fun setupScreenCapture() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        // 反馈给 ViewModel 系统端确切的物理宽度与高度
        LanRemoteViewModel.instance?.onScreenSizeDetermined(width, height)

        // 为了极大节约移动端局域网的网络传输带宽开销，将采集宽高整体降级为正常宽高的 0.35 倍，且对齐到 16 字节
        var captureWidth = (width * 0.35f).toInt()
        var captureHeight = (height * 0.35f).toInt()
        captureWidth = (captureWidth / 16) * 16
        captureHeight = (captureHeight / 16) * 16
        if (captureWidth <= 0) captureWidth = 360
        if (captureHeight <= 0) captureHeight = 640

        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, captureWidth, captureHeight)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000) // 2 Mbps 比特率
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30) // 30 帧速率 (FPS)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 秒一个关键帧 (I-Frame)

            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codecSurface = codec.createInputSurface()
            mediaCodec = codec
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create MediaCodec encoder: ${e.message}")
            LanRemoteViewModel.instance?.addServerLog("初始化视频编码器失败: ${e.message}", com.example.ui.LogType.WARNING)
            stopSelf()
            return
        }
        
        // 注册多媒体投影会话生命周期监听拦截回调 (在 Android 14+ 下极为关键，必须注册此监听方可启动渲染采集)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection stopped")
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))

        // 建立虚拟显示层，连结屏幕和 MediaCodec Input Surface 端口，零拷贝，CPU 几乎零开销
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            captureWidth,
            captureHeight,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            codecSurface,
            null,
            null
        )

        // 启动硬件流编码
        mediaCodec?.start()
        isEncoding = true
        startEncodingLoop()

        LanRemoteViewModel.instance?.addServerLog("投屏引擎并硬件视频编码开启成功", com.example.ui.LogType.SUCCESS)
    }

    /**
     * 轮询拉取 MediaCodec 编码好的视频流帧，将其并转换打包通过 Socket 底层网络分发
     */
    private fun startEncodingLoop() {
        codecThread = Thread {
            val bufferInfo = MediaCodec.BufferInfo()
            val vm = LanRemoteViewModel.instance
            while (isEncoding) {
                try {
                    val codec = mediaCodec ?: break
                    val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                    if (outputBufferIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val outData = ByteArray(bufferInfo.size)
                            outputBuffer.get(outData)
                            
                            val base64Data = Base64.encodeToString(outData, Base64.NO_WRAP)
                            val flags = bufferInfo.flags
                            vm?.onEncodedFrameCaptured(base64Data, flags, bufferInfo.presentationTimeUs)
                        }
                        codec.releaseOutputBuffer(outputBufferIndex, false)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Encoding loop interval packet: ${e.message}")
                }
            }
        }.apply { start() }
    }

    /**
     * 建立并在系统服务下注册低功耗前台通知管道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "屏幕共享后台服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "正在后台捕获并发送屏幕数据流"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * 组建前台服务的不可关闭粘性常驻通知栏
     */
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("屏幕共享共控服务运作中")
            .setContentText("服务端正在同步录屏画面并等待接收触摸控制指令")
            .setSmallIcon(com.example.R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }

    /**
     * 释放所有占用的物理投影采集、线程、监听以及显示组件
     */
    override fun onDestroy() {
        updateRunningState(false)
        isEncoding = false
        try {
            codecThread?.interrupt()
            codecThread = null
        } catch (ignored: Exception) {}
        
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (ignored: Exception) {}
        mediaCodec = null
        
        try {
            codecSurface?.release()
        } catch (ignored: Exception) {}
        codecSurface = null

        virtualDisplay?.release()
        mediaProjection?.stop()
        Log.i(TAG, "ScreenCaptureService Stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

