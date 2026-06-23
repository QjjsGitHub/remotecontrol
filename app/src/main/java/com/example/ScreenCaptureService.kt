package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.data.model.LogType
import com.example.data.model.ScreenSize
import com.example.data.repository.RemoteControlRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 屏幕捕获与画面广播的前台服务 (ScreenCaptureService)
 * 启动系统投屏机制，获取实时屏幕数据并进行格式压缩，提供给共控端显示并进行反向控制
 */
@AndroidEntryPoint
class ScreenCaptureService : Service() {

    @Inject
    lateinit var repository: RemoteControlRepository

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "ScreenCaptureChannel"
        private const val NOTIFICATION_ID = 8821

        // 使用 StateFlow 实现运行状态的响应式暴露，这样可以在 Jetpack Compose UI 中完美响应
        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

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
     * 【关键优化】本地缓存 SPS/PPS 配置帧。
     * 每次编码器产生关键帧时，我们都先补发一次此配置，确保客户端随时接入都能立即解码。
     */
    private var cachedConfigFrame: ByteArray? = null


    private val displayManager by lazy {
        getSystemService(DISPLAY_SERVICE) as DisplayManager
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        private var lastDisplayWidth = 0
        private var lastDisplayHeight = 0

        override fun onDisplayChanged(displayId: Int) {
            // 只有主屏幕变化时才处理
            if (displayId == Display.DEFAULT_DISPLAY) {
                val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
                val metrics = DisplayMetrics()
                // 获取旋转后的真实物理像素
                windowManager.defaultDisplay.getRealMetrics(metrics)

                val currentWidth = metrics.widthPixels
                val currentHeight = metrics.heightPixels

                // 【核心修复】如果宽和高没有发生实质性变化，说明是同一个旋转周期的多余回调，直接拦截
                if (currentWidth == lastDisplayWidth && currentHeight == lastDisplayHeight) {
                    return
                }

                // 更新记录的宽高
                lastDisplayWidth = currentWidth
                lastDisplayHeight = currentHeight

                Log.i(
                    "ScreenCapture",
                    "检测到屏幕旋转且尺寸改变: ${currentWidth}x${currentHeight}"
                )

                // 更新记录的宽高
                repository.setServerScreenSize(currentWidth, currentHeight)
                serviceScope.launch {
                    repository.broadcastScreenSize(ScreenSize(currentWidth, currentHeight, if (currentWidth > currentHeight) 1 else 0))
                }

                // 现在这里只会在真正旋转完成时执行一次
                restartVirtualDisplay(currentWidth, currentHeight, metrics.densityDpi)
            }
        }

        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
    }

    /**
     * 屏幕旋转或分辨率改变时，复用已授权的 MediaProjection 动态重启并重设编码尺寸
     */
    /**
     * 屏幕旋转时，遵守 Android 14+ 规范：
     * 不重新调用 createVirtualDisplay，而是通过 resize() 和 setSurface() 动态更新目标
     */
    private fun restartVirtualDisplay(width: Int, height: Int, density: Int) {
        if (virtualDisplay == null) return

        // 1. 暂停现有的视频分发循环
        isEncoding = false
        try {
            codecThread?.interrupt()
            codecThread?.join(500)
        } catch (_: Exception) {
        }
        codecThread = null

        // 2. 释放旧的 MediaCodec 和 Surface
        try {
            mediaCodec?.apply {
                stop()
                release()
            }
        } catch (_: Exception) {
        }
        mediaCodec = null

        try {
            codecSurface?.release()
        } catch (_: Exception) {
        }
        codecSurface = null

        // 3. 计算旋转后的新录屏分辨率 (对齐到 16 字节)
        val (captureWidth, captureHeight) = calculateCaptureSize(width, height)

        // 4. 使用新分辨率重新创建并配置 MediaCodec
        try {
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                captureWidth,
                captureHeight
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codecSurface = createInputSurface()
                start()
            }
        } catch (e: Exception) {
            repository.addServerLog(
                "重建编码器失败: ${e.message}",
                LogType.WARNING
            )
            return
        }

        // 5. 更新 VirtualDisplay
        try {
            virtualDisplay?.apply {
                surface = null
                resize(captureWidth, captureHeight, density)
                surface = codecSurface
            }
        } catch (e: Exception) {
            repository.addServerLog(
                "调整虚拟显示失败: ${e.message}",
                LogType.WARNING
            )
            return
        }

        isEncoding = true
        startEncodingLoop()
        repository.addServerLog(
            "屏幕自适应完成: ${captureWidth}x${captureHeight}",
            LogType.SUCCESS
        )
    }

    /**
     * 服务初始化生命周期
     */
    override fun onCreate() {
        super.onCreate()
        updateRunningState(true)
        mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))


    }

    /**
     * 服务通过 startService 开始执行
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = createNotification()

        // 在 Android Q 及以上版本，前台服务必须明确声明 mediaProjection TYPE，否则会导致崩溃或启动失败
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // 获取来自 Activity 启动授权后传入的屏幕捕捉会话结果代码与授权信息
        val resultCode = intent?.getIntExtra("RESULT_CODE", android.app.Activity.RESULT_CANCELED)
            ?: android.app.Activity.RESULT_CANCELED
        val data = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra("DATA", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra("DATA")
            }
        } catch (_: Throwable) {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("DATA")
        }

        // 验证系统的屏幕投射许可结果是否已经被正常授予
        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            Log.e(TAG, "Result code is not OK or data intent is null. Stopping service...")
            repository.addServerLog(
                "投屏启动失败：未获得用户授权",
                LogType.WARNING
            )
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)
            if (mediaProjection == null) {
                repository.addServerLog(
                    "投屏授权失败: 实例为空",
                    LogType.WARNING
                )
                stopSelf()
                return START_NOT_STICKY
            }
        } catch (e: Exception) {
            repository.addServerLog(
                "创建投屏引擎异常: ${e.message}",
                LogType.WARNING
            )
            stopSelf()
            return START_NOT_STICKY
        }

        setupScreenCapture()
        return START_STICKY
    }

    /**
     * 配置屏幕采集参数并注册底层帧监听，开启流畅地渲染循环机制
     */
    private fun setupScreenCapture() {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        // 反馈给 Repository 系统端确切的物理宽度与高度
        repository.setServerScreenSize(width, height)
        serviceScope.launch {
            repository.broadcastScreenSize(ScreenSize(width, height, if (width > height) 1 else 0))
        }

        // 为了极大节约移动端局域网的网络传输带宽开销，将采集宽高整体降级为正常宽高的 0.35 倍，且对齐到 16 字节
        val (captureWidth, captureHeight) = calculateCaptureSize(width, height)

        try {
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                captureWidth,
                captureHeight
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codecSurface = createInputSurface()
            }
        } catch (e: Exception) {
            repository.addServerLog(
                "初始化视频编码器失败: ${e.message}",
                LogType.WARNING
            )
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

        repository.addServerLog(
            "投屏引擎并硬件视频编码开启成功",
            LogType.SUCCESS
        )
    }

    /**
     * 轮询拉取 MediaCodec 编码好的视频流帧，将其并转换打包通过 Socket 底层网络分发
     */
    private fun startEncodingLoop() {
        codecThread = Thread {
            val bufferInfo = MediaCodec.BufferInfo()
            while (isEncoding && !Thread.currentThread().isInterrupted) {
                try {
                    val codec = mediaCodec ?: break
                    val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                    if (outputBufferIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            val flags = bufferInfo.flags
                            val pts = bufferInfo.presentationTimeUs

                            // --- 周期性补发逻辑核心开始 ---
                            if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                // 1. 配置帧（SPS/PPS）频率极低（每秒或每分钟才一次），单独拷贝一份用于缓存
                                val configData = ByteArray(bufferInfo.size)
                                outputBuffer.get(configData)
                                cachedConfigFrame = configData
                                Log.d(TAG, "已捕获并缓存最新编码器配置 (SPS/PPS)")
                            } else if (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) {
                                // 2. 如果它是一个关键帧 (I-Frame)，检查是否有缓存的配置
                                cachedConfigFrame?.let { config ->
                                    serviceScope.launch {
                                        repository.broadcastVideoFrame(
                                            config,
                                            MediaCodec.BUFFER_FLAG_CODEC_CONFIG,
                                            pts
                                        )
                                    }
                                }
                            }
                            // --- 周期性补发逻辑核心结束 ---

                            // 直接把硬件 ByteBuffer 丢给 Repository 处理
                            val size = bufferInfo.size
                            val packet = ByteArray(size)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + size)
                            outputBuffer.get(packet)
                            
                            serviceScope.launch {
                                repository.broadcastVideoFrame(packet, flags, pts)
                            }
                        }
                        codec.releaseOutputBuffer(outputBufferIndex, false)
                    }
                } catch (e: Exception) {
                    if (isEncoding) {
                        Log.d(TAG, "线程中断: ${e.message}")
                    } else {
                        Log.e(TAG, "编码异常: ${e.message}")
                    }
                }
            }
        }.apply { start() }
    }

    /**
     * 计算并对齐录屏采集的分辨率
     */
    private fun calculateCaptureSize(width: Int, height: Int): Pair<Int, Int> {
        var captureWidth = ((width * 0.8).toInt() / 16) * 16
        var captureHeight = ((height * 0.8).toInt() / 16) * 16
        if (captureWidth <= 0) captureWidth = 1080
        if (captureHeight <= 0) captureHeight = 2400
        return Pair(captureWidth, captureHeight)
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
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
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
            .setSmallIcon(R.drawable.ic_launcher_foreground)
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
        } catch (_: Exception) {
        }

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (_: Exception) {
        }
        mediaCodec = null

        try {
            codecSurface?.release()
        } catch (_: Exception) {
        }
        codecSurface = null

        virtualDisplay?.release()
        mediaProjection?.stop()
        Log.i(TAG, "ScreenCaptureService Stopped")

        displayManager.unregisterDisplayListener(displayListener)

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

