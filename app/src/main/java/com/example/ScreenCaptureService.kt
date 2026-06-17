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
import java.io.ByteArrayOutputStream

/**
 * 屏幕捕获与画面广播的前台服务 (ScreenCaptureService)
 * 启动系统投屏机制，获取实时屏幕数据并进行格式压缩，提供给共控端显示并进行反向控制
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "ScreenCaptureChannel"
        private const val NOTIFICATION_ID = 8821
        
        /**
         * 记录后台流式屏幕截取服务是否正在运行的全局标识
         */
        var isRunning = false
            private set
    }

    // 系统多媒体投影管理器 (用于请求和管理屏幕录制)
    private var mediaProjectionManager: MediaProjectionManager? = null
    // 媒体投影连接会话
    private var mediaProjection: MediaProjection? = null
    // 系统虚拟显示器 (将屏幕物理像素投影到我们自定义的 ImageReader 表面上)
    private var virtualDisplay: VirtualDisplay? = null
    // 原始图像读取器，负责监听和解析屏幕上变动的像素帧
    private var imageReader: ImageReader? = null
    
    // 自定义图像后处理专用的 HandlerThread (完全在后台运行，防止解析图片或压缩 Base64 时卡住 UI 主线程)
    private var backgroundThread: android.os.HandlerThread? = null
    private var backgroundHandler: Handler? = null
    // 记录上一帧抓取和分发的时间戳，用于节约网络带宽
    private var lastFrameTime = 0L
    // 每帧抓取的最小间隔时间(250毫秒约秒级4帧，网络耗损低，表现最稳定)
    private val frameRateMs = 250L 

    /**
     * 服务初始化生命周期
     */
    override fun onCreate() {
        super.onCreate()
        isRunning = true
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    /**
     * 服务通过 startService 开始执行
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = createNotification()
        
        // 在 Android Q 及以上版本，前台服务必须明确声明 mediaProjection 类型，否则会导致崩溃或启动失败
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
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        // 反馈给 ViewModel 系统端确切的物理宽度与高度
        LanRemoteViewModel.instance?.onScreenSizeDetermined(width, height)

        // 为了极大节约移动端局域网的网络传输带宽开销，将采集宽高整体降级为正常宽高的 0.35 倍
        val captureWidth = (width * 0.35f).toInt()
        val captureHeight = (height * 0.35f).toInt()

        // 实例化一个双重缓冲队列图像读取器 (Format: RGBA_8888, Buffer count: 2)
        imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
        
        // 注册多媒体投影会话生命周期监听拦截回调 (在 Android 14+ 下极为关键，必须注册此监听方可启动渲染采集)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection stopped")
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))

        // 建立虚拟显示层，连结屏幕和自定义 ImageReader Surface 端口
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            captureWidth,
            captureHeight,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )

        // 开启独立的后台循环，让所有的图片解析、缓存剪裁剪裁、压缩等全流程都在本后台非阻塞线程进行
        backgroundThread = android.os.HandlerThread("ScreenCaptureBackground").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        // 绑定图像监听回调至后台处理器
        imageReader?.setOnImageAvailableListener({ reader ->
            val now = System.currentTimeMillis()
            // 如果满足间隔限制(帧率卡控)，开始捕获解析并进行广播
            if (now - lastFrameTime >= frameRateMs) {
                lastFrameTime = now
                acquireAndBroadcastFrame(captureWidth, captureHeight)
            } else {
                // 否则立刻丢弃最新变动的无效缓存帧，保障 ImageReader 渲染管道没有任何物理滞后挤压
                var img: android.media.Image? = null
                try {
                    img = reader.acquireLatestImage()
                } catch (ignored: Exception) {
                } finally {
                    img?.close()
                }
            }
        }, backgroundHandler)

        LanRemoteViewModel.instance?.addServerLog("投屏引擎并后台采集开启成功", com.example.ui.LogType.SUCCESS)
    }

    /**
     * 提取当前 Surface 产生的图像帧，整理格式并将其转换为精简的 Base64 广播包
     */
    private fun acquireAndBroadcastFrame(width: Int, height: Int) {
        val reader = imageReader ?: return
        val vm = LanRemoteViewModel.instance ?: return

        var image: android.media.Image? = null
        try {
            image = reader.acquireLatestImage() ?: return
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            // 如果行跨度对齐补全含有空闲像素，需要计算行边距差
            val rowPadding = rowStride - pixelStride * width

            // 基于物理像素字节读取生成 ARGB_8888 结构化位图
            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // 如果对齐机制导致了产生右侧空白边距，我们进行裁剪，只剔除多余的黑框部分
            val cleanBitmap = if (rowPadding > 0) {
                try {
                    val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                    bitmap.recycle() // 及时回收中转的大内存未对齐位图
                    cropped
                } catch (e: Exception) {
                    bitmap
                }
            } else {
                bitmap
            }

            // 对规范化裁剪后的纯净位图进行 JPEG 图像超高比例有损压缩 (质量45，兼顾色彩的同时实现大小极小)
            val stream = ByteArrayOutputStream()
            cleanBitmap.compress(Bitmap.CompressFormat.JPEG, 45, stream)
            val bytes = stream.toByteArray()
            
            // 手动回收使用完的位图碎片，确保不发生内存抖动或长久积累后的 OOM
            if (cleanBitmap != bitmap) {
                cleanBitmap.recycle()
            } else {
                bitmap.recycle()
            }
            
            // 无换行的高性能 Base64 编码
            val base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)
            
            // 提交至 ViewModel 将 Base64 推送到所有连接局域网的屏幕共控控制端
            vm.onFrameCaptured(base64Data, width, height)
        } catch (e: Throwable) {
            Log.e(TAG, "Frame capture / encoding exception: ${e.message}")
        } finally {
            try {
                image?.close()
            } catch (ignored: Exception) {}
        }
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
        isRunning = false
        imageReader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
        backgroundThread?.quitSafely()
        Log.i(TAG, "ScreenCaptureService Stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

