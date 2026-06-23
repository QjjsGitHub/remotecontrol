package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.data.model.ConnectionState
import com.example.data.model.LogType
import com.example.data.repository.RemoteControlRepository
import com.example.ui.FloatingWindowViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

/**
 * 后台安全全局悬浮窗控制服务 (FloatingWindowService)
 * 遵循严格的 MVVM 模式。Service 作为 View 的宿主，不直接处理业务逻辑，而是观察 ViewModel 发出的指令。
 */
@AndroidEntryPoint
class FloatingWindowService : Service(), LifecycleOwner, ViewModelStoreOwner,
    SavedStateRegistryOwner {

    @Inject
    lateinit var repository: RemoteControlRepository

    companion object {
        const val TAG = "FloatingWindowService"
        private const val CHANNEL_ID = "FloatingWindowService"
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private var windowManager: WindowManager? = null
    private var floatingView: ComposeView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        repository.setFloatingWindowRunning(true)

        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleRegistry.currentState = Lifecycle.State.STARTED

        createNotificationChannel()
        startForeground(9922, createNotification())

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        setupFloatingWindow()
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
        return NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle("远程悬浮窗已打开")
            .setContentText("正在显示远程画面").setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true).build()
    }

    private fun setupFloatingWindow() {
        val wm = windowManager ?: return
        val dm = resources.displayMetrics

        // 1. 预设初始大小，避免 WRAP_CONTENT 导致的测量闪烁
        val initialScale = repository.floatingScaleMultiplier.value
        val screenWidth = dm.widthPixels

        val mWidth = repository.mirroredWidth.value
        val mHeight = repository.mirroredHeight.value
        val aspectRatio = if (mWidth > 0) mHeight.toFloat() / mWidth.toFloat() else 1.77f

        // 初始高度使用服务端发来的比例 + Header 高度(24dp)
        val initialWidth = (screenWidth * initialScale).toInt()
        val initialHeight = (initialWidth * aspectRatio).toInt() + (24 * dm.density).toInt()

        val params = WindowManager.LayoutParams(
            initialWidth,
            initialHeight,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 150
            y = 350
        }

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingWindowService)
            setViewTreeViewModelStoreOwner(this@FloatingWindowService)
            setViewTreeSavedStateRegistryOwner(this@FloatingWindowService)
        }

        composeView.setContent {
            val viewModel: FloatingWindowViewModel =
                viewModel(factory = object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return FloatingWindowViewModel(repository) as T
                    }
                })

            // MVVM 核心：宿主观察 ViewModel 的事件流
            LaunchedEffect(Unit) {
                launch {
                    viewModel.closeEvent.collect { stopSelf() }
                }
                launch {
                    viewModel.connectionState.collectLatest { state ->
                        if (state == ConnectionState.Disconnected) stopSelf()
                    }
                }
            }

            FloatingWindowContent(viewModel = viewModel, onMove = { dx, dy ->
                params.x += dx.toInt()
                params.y += dy.toInt()
                wm.updateViewLayout(composeView, params)
            }, onResize = { widthPx, heightPx ->
                if (kotlin.math.abs(params.width - widthPx) > 2 || kotlin.math.abs(params.height - heightPx) > 2) {
                    params.width = widthPx
                    params.height = heightPx
                    wm.updateViewLayout(composeView, params)
                }
            }, onUpdateLayout = { wm.updateViewLayout(composeView, params) })
        }

        wm.addView(composeView, params)
        floatingView = composeView
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override fun onDestroy() {
        repository.setFloatingWindowRunning(false)
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        floatingView?.let { windowManager?.removeView(it) }
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        super.onDestroy()
    }
}

@Composable
fun FloatingWindowContent(
    viewModel: FloatingWindowViewModel,
    onMove: (Float, Float) -> Unit,
    onResize: (Int, Int) -> Unit,
    onUpdateLayout: () -> Unit
) {
    val hasFrameReceived by viewModel.hasFrameReceived.collectAsState()
    val mirroredWidth by viewModel.mirroredWidth.collectAsState()
    val mirroredHeight by viewModel.mirroredHeight.collectAsState()
    val scaleMultiplier by viewModel.scaleMultiplier.collectAsState()
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current // 使用配置信息作为屏幕基准

    var isTouchLocked by remember { mutableStateOf(false) }

    val aspectRatio =
        if (mirroredWidth > 0) mirroredHeight.toFloat() / mirroredWidth.toFloat() else 16f / 9f
    // 2. 纠正基准：使用屏幕总宽度作为缩放系数的基数
    val clientScreenWidthDp = configuration.screenWidthDp.toFloat()
    val windowWidthDp = clientScreenWidthDp * scaleMultiplier
    val windowHeightDp = windowWidthDp * aspectRatio
    val isCompact = windowWidthDp < 180

    LaunchedEffect(windowWidthDp, windowHeightDp) {
        val d = density.density
        onResize((windowWidthDp * d).toInt(), ((windowHeightDp + 24) * d).toInt())
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .border(2.dp, Color(0xFF6200EE), RoundedCornerShape(16.dp))
            .background(Color.Black)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .background(Color(0xFF6200EE))
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            onMove(dragAmount.x, dragAmount.y)
                        }
                    }
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly) {
                if (!isCompact) {
                    Text(
                        text = if (isTouchLocked) "缩放: ${(scaleMultiplier * 100).toInt()}%" else "控屏: ${(scaleMultiplier * 100).toInt()}%",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(
                        onClick = { viewModel.sendCommand("BACK") }, modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            "Back",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                IconButton(
                    onClick = { viewModel.sendCommand("HOME") }, modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.Home,
                        "Home",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = { isTouchLocked = !isTouchLocked }, modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.Lock,
                        "Lock",
                        tint = if (isTouchLocked) Color.Red else Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = { viewModel.requestClose() }, modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        "Close",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Video Area
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (hasFrameReceived) {
                    var localViewW by remember { mutableIntStateOf(1) }
                    var localViewH by remember { mutableIntStateOf(1) }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(isTouchLocked) {
                                if (isTouchLocked) {
                                    detectTransformGestures { _, _, zoom, _ ->
                                        viewModel.updateScale(
                                            zoom
                                        )
                                    }
                                }
                            }) {
                        VideoSurfaceViewer(
                            viewModel = viewModel,
                            onSurfaceCreatedPoke = onUpdateLayout,
                            modifier = Modifier
                                .fillMaxSize()
                                .onSizeChanged { localViewW = it.width; localViewH = it.height }
                                .pointerInput(localViewW, localViewH, isTouchLocked) {
                                    if (isTouchLocked || localViewW <= 0 || localViewH <= 0) return@pointerInput
                                    val viewW = localViewW.toFloat()
                                    val viewH = localViewH.toFloat()
                                    var lastMoveTime = 0L

                                    kotlinx.coroutines.coroutineScope {
                                        launch {
                                            detectTapGestures(
                                                onTap = { viewModel.sendCommand("TAP:${it.x / viewW},${it.y / viewH}") },
                                                onDoubleTap = { viewModel.sendCommand("DOUBLE_TAP:${it.x / viewW},${it.y / viewH}") },
                                                onLongPress = { viewModel.sendCommand("LONG_PRESS:${it.x / viewW},${it.y / viewH}") })
                                        }
                                        launch {
                                            detectDragGestures(
                                                onDragStart = { viewModel.sendCommand("DOWN:${it.x / viewW},${it.y / viewH}") },
                                                onDragEnd = { viewModel.sendCommand("UP") },
                                                onDragCancel = { viewModel.sendCommand("UP") },
                                                onDrag = { change, _ ->
                                                    change.consume()
                                                    val now = System.currentTimeMillis()
                                                    if (now - lastMoveTime >= 16L) {
                                                        viewModel.sendCommand("MOVE:${change.position.x / viewW},${change.position.y / viewH}")
                                                        lastMoveTime = now
                                                    }
                                                })
                                        }
                                    }
                                })
                        if (isTouchLocked) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(0.45f)),
                                Alignment.Center
                            ) {
                                Text(
                                    "触摸锁定\n双指缩放\n比例: ${(scaleMultiplier * 100).toInt()}%",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                } else {
                    Text("等待远端投屏...", color = Color.Gray, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun VideoSurfaceViewer(
    viewModel: FloatingWindowViewModel,
    onSurfaceCreatedPoke: () -> Unit,
    modifier: Modifier = Modifier
) {
    val videoWidth by viewModel.mirroredWidth.collectAsState()
    val videoHeight by viewModel.mirroredHeight.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()

    androidx.compose.runtime.key(videoWidth, videoHeight, connectionState) {
        var decoderToken by remember { mutableIntStateOf(0) }
        var decoder by remember { mutableStateOf<MediaCodec?>(null) }

        LaunchedEffect(Unit) {
            delay(200.milliseconds)
            onSurfaceCreatedPoke()
        }

        LaunchedEffect(decoderToken) {
            val codec = decoder ?: return@LaunchedEffect
            viewModel.videoFrames.collect { frame ->
                try {
                    val inputIndex = codec.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        codec.getInputBuffer(inputIndex)?.apply {
                            clear()
                            put(frame.data)
                            codec.queueInputBuffer(
                                inputIndex, 0, frame.data.size, frame.pts, frame.flags
                            )
                        }
                    }
                    val info = MediaCodec.BufferInfo()
                    var outputIndex = codec.dequeueOutputBuffer(info, 0)
                    while (outputIndex >= 0) {
                        codec.releaseOutputBuffer(outputIndex, true)
                        outputIndex = codec.dequeueOutputBuffer(info, 0)
                    }
                } catch (e: Exception) {
                    viewModel.addLog("解码失败: ${e.message}")
                }
            }
        }

        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            try {
                                val codec =
                                    MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                                val format = MediaFormat.createVideoFormat(
                                    MediaFormat.MIMETYPE_VIDEO_AVC, videoWidth, videoHeight
                                )
                                codec.configure(format, holder.surface, null, 0)
                                codec.start()
                                decoder = codec
                                decoderToken++
                            } catch (e: Exception) {
                                viewModel.addLog("构建解码器失败: ${e.message}")
                            }
                        }

                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h2: Int) {}
                        override fun surfaceDestroyed(h: SurfaceHolder) {
                            val c = decoder; decoder = null
                            try {
                                c?.stop(); c?.release()
                            } catch (_: Exception) {
                            }
                        }
                    })
                }
            }, modifier = modifier
        )
    }
}
