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
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 屏幕捕获与画面广播的前台服务 (ScreenCaptureService)
 * 遵循 MVVM 模式。Service 作为数据的“生产者”，将状态和数据同步至 Repository。
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
    }

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaCodec: MediaCodec? = null
    private var codecSurface: Surface? = null
    private var codecThread: Thread? = null
    private var isEncoding = false
    private var cachedConfigFrame: ByteArray? = null

    private val displayManager by lazy { getSystemService(DISPLAY_SERVICE) as DisplayManager }

    private val displayListener = object : DisplayManager.DisplayListener {
        private var lastDisplayWidth = 0
        private var lastDisplayHeight = 0

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
                val metrics = DisplayMetrics()
                windowManager.defaultDisplay.getRealMetrics(metrics)

                val currentWidth = metrics.widthPixels
                val currentHeight = metrics.heightPixels

                if (currentWidth == lastDisplayWidth && currentHeight == lastDisplayHeight) return

                lastDisplayWidth = currentWidth
                lastDisplayHeight = currentHeight

                repository.setServerScreenSize(currentWidth, currentHeight)
                serviceScope.launch {
                    repository.broadcastScreenSize(
                        ScreenSize(
                            currentWidth, currentHeight, if (currentWidth > currentHeight) 1 else 0
                        )
                    )
                }
                restartVirtualDisplay(currentWidth, currentHeight, metrics.densityDpi)
            }
        }

        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
    }

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }

        val resultCode = intent?.getIntExtra("RESULT_CODE", android.app.Activity.RESULT_CANCELED)
            ?: android.app.Activity.RESULT_CANCELED
        val data = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra("DATA", Intent::class.java)
            } else {
                @Suppress("DEPRECATION") intent?.getParcelableExtra("DATA")
            }
        } catch (_: Throwable) {
            @Suppress("DEPRECATION") intent?.getParcelableExtra("DATA")
        }

        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            repository.addServerLog("投屏启动失败：未获得用户授权", LogType.WARNING)
            stopSelf()
            return START_NOT_STICKY
        }

        mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)
        if (mediaProjection == null) {
            repository.addServerLog("投屏授权失败: 实例为空", LogType.WARNING)
            stopSelf()
            return START_NOT_STICKY
        }

        setupScreenCapture()
        repository.setScreenCaptureRunning(true) // 更新 Repository 中的状态
        return START_STICKY
    }

    private fun setupScreenCapture() {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        repository.setServerScreenSize(width, height)
        serviceScope.launch {
            repository.broadcastScreenSize(ScreenSize(width, height, if (width > height) 1 else 0))
        }

        val (captureWidth, captureHeight) = calculateCaptureSize(width, height)

        try {
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, captureWidth, captureHeight
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
            repository.addServerLog("初始化视频编码器失败: ${e.message}", LogType.WARNING)
            stopSelf()
            return
        }

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))

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

        isEncoding = true
        startEncodingLoop()
        repository.addServerLog("投屏引擎并硬件视频编码开启成功", LogType.SUCCESS)
    }

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

                            if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                val configData = ByteArray(bufferInfo.size)
                                outputBuffer.get(configData)
                                cachedConfigFrame = configData
                            } else if (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) {
                                cachedConfigFrame?.let { config ->
                                    serviceScope.launch {
                                        repository.broadcastVideoFrame(
                                            config, MediaCodec.BUFFER_FLAG_CODEC_CONFIG, pts
                                        )
                                    }
                                }
                            }

                            val packet = ByteArray(bufferInfo.size)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            outputBuffer.get(packet)

                            serviceScope.launch {
                                repository.broadcastVideoFrame(
                                    packet, flags, pts
                                )
                            }
                        }
                        codec.releaseOutputBuffer(outputBufferIndex, false)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Encoding loop exception: ${e.message}")
                }
            }
        }.apply { start() }
    }

    private fun restartVirtualDisplay(width: Int, height: Int, density: Int) {
        if (virtualDisplay == null) return
        isEncoding = false
        try {
            codecThread?.interrupt(); codecThread?.join(500)
        } catch (_: Exception) {
        }
        codecThread = null

        try {
            mediaCodec?.apply { stop(); release() }
        } catch (_: Exception) {
        }
        mediaCodec = null
        try {
            codecSurface?.release()
        } catch (_: Exception) {
        }
        codecSurface = null

        val (captureWidth, captureHeight) = calculateCaptureSize(width, height)
        try {
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, captureWidth, captureHeight
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
            repository.addServerLog("重建编码器失败: ${e.message}", LogType.WARNING)
            return
        }

        try {
            virtualDisplay?.apply {
                surface = null
                resize(captureWidth, captureHeight, density)
                surface = codecSurface
            }
        } catch (e: Exception) {
            repository.addServerLog("调整虚拟显示失败: ${e.message}", LogType.WARNING)
            return
        }

        isEncoding = true
        startEncodingLoop()
        repository.addServerLog("屏幕自适应完成: ${captureWidth}x${captureHeight}", LogType.SUCCESS)
    }

    private fun calculateCaptureSize(width: Int, height: Int): Pair<Int, Int> {
        var captureWidth = ((width * 0.8).toInt() / 16) * 16
        var captureHeight = ((height * 0.8).toInt() / 16) * 16
        if (captureWidth <= 0) captureWidth = 1080
        if (captureHeight <= 0) captureHeight = 2400
        return Pair(captureWidth, captureHeight)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "屏幕共享后台服务", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("屏幕共享共控服务运作中").setContentText("正在同步录屏画面...")
            .setSmallIcon(R.drawable.ic_launcher_foreground).setOngoing(true).build()
    }

    override fun onDestroy() {
        repository.setScreenCaptureRunning(false)
        isEncoding = false
        try {
            codecThread?.interrupt(); codecThread = null
        } catch (_: Exception) {
        }
        try {
            mediaCodec?.stop(); mediaCodec?.release()
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
        displayManager.unregisterDisplayListener(displayListener)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
