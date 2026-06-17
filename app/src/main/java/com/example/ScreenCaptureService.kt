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

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "ScreenCaptureChannel"
        private const val NOTIFICATION_ID = 8821
        
        var isRunning = false
            private set
    }

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    
    private var captureHandler: Handler? = null
    private var captureRunnable: Runnable? = null
    private val frameRateMs = 250L // 4 frames per second is super stable and preserves network bandwidth

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = createNotification()
        
        // Under API 29+, specify FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val resultCode = intent?.getIntExtra("RESULT_CODE", -1) ?: -1
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra<Intent>("DATA")
        }

        if (resultCode == -1 || data == null) {
            Log.e(TAG, "Result code or data intent is null. Stopping service...")
            stopSelf()
            return START_NOT_STICKY
        }

        mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)
        if (mediaProjection == null) {
            Log.e(TAG, "Failed to get MediaProjection instance")
            stopSelf()
            return START_NOT_STICKY
        }

        setupScreenCapture()
        return START_STICKY
    }

    private fun setupScreenCapture() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        // Feed actual size info to view-model so clients know screen layout dimensions
        LanRemoteViewModel.instance?.onScreenSizeDetermined(width, height)

        // To save bandwidth, scale captured frame size (e.g. 1/3 scale to maintain exact performance)
        val captureWidth = (width * 0.35f).toInt()
        val captureHeight = (height * 0.35f).toInt()

        imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
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

        // Setup capture polling loop with some rate limiting
        captureHandler = Handler(Looper.getMainLooper())
        captureRunnable = object : Runnable {
            override fun run() {
                acquireAndBroadcastFrame(captureWidth, captureHeight)
                captureHandler?.postDelayed(this, frameRateMs)
            }
        }
        captureRunnable?.let { captureHandler?.post(it) }
    }

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
            val rowPadding = rowStride - pixelStride * width

            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // Compress to JPEG size-friendly format
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 45, stream)
            val bytes = stream.toByteArray()
            
            val base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)
            
            // Send back to the view model to distribute to the connection client
            vm.onFrameCaptured(base64Data, width, height)
        } catch (e: Throwable) {
            Log.e(TAG, "Frame capture / encoding exception: ${e.message}")
        } finally {
            try {
                image?.close()
            } catch (ignored: Exception) {}
        }
    }

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

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("屏幕共享共控服务运作中")
            .setContentText("服务端正在同步录屏画面并等待接收触摸控制指令")
            .setSmallIcon(com.example.R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        captureRunnable?.let { captureHandler?.removeCallbacks(it) }
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
        Log.i(TAG, "ScreenCaptureService Stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
