package com.example

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
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
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.data.model.ConnectionState
import com.example.data.repository.RemoteControlRepository
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
 * 该服务允许在不处于前台 activity 的情况下，常驻在系统桌面之上显示 1/2 比例的受控画面，
 * 并支持实时的 TAP 与 SWIPE 反向遥控手势操作注入。
 */
@AndroidEntryPoint
class FloatingWindowService : Service(), LifecycleOwner, ViewModelStoreOwner,
    SavedStateRegistryOwner {

    @Inject
    lateinit var repository: RemoteControlRepository

    companion object {
        const val TAG = "FloatingWindowService"

        /**
         * 其他后台线程（如协程、网络监听器）能立即看到最新的运行状态。
         */
        // 核心：使用 StateFlow 管理状态
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    }

    // 后台生命周期控制器，用于供给 ComposeView 所需的生命周期环境
    private val lifecycleRegistry = LifecycleRegistry(this)

    // 页面存储容器，用于托管和追踪 ViewModel 依赖
    private val store = ViewModelStore()

    // 已保存状态的注册控制器，供给 Compose 组件树的底层架构环境
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    // 提供生命周期接口实现
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    // 提供 ViewModel 存储实现
    override val viewModelStore: ViewModelStore get() = store

    // 提供状态存档恢复实现
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    // 系统窗口管理员，用于在桌面上层添加/更新/移除全局悬浮窗组件
    private var windowManager: WindowManager? = null

    // 自定义的 Jetpack Compose 视图容器，可直接塞入悬浮窗中
    private var floatingView: ComposeView? = null

    // 悬浮窗属于后台常驻无绑定服务，默认返回 null
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 服务初始化生命周期
     */
    override fun onCreate() {
        super.onCreate()
        _isRunning.value = true
        Log.i(TAG, "FloatingWindowService onCreate")

        // 激活生命周期状态
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleRegistry.currentState = Lifecycle.State.STARTED

        // 绑定窗口管理员并创建浮空视窗
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        setupFloatingWindow()

        // 监听对端的连接存活状态 —— 一旦网络断开或宿主关闭，自动销毁悬浮窗服务确保系统纯净
        lifecycleScope.launch {
            repository.clientConnectionState.collectLatest { state ->
                if (state == ConnectionState.Disconnected) {
                    Log.i(TAG, "Connection lost, closing floating window")
                    stopSelf()
                }
            }
        }
    }

    /**
     * 创建并装载全局系统级悬浮窗 (SYSTEM_ALERT_WINDOW)
     */
    private fun setupFloatingWindow() {
        val context = this
        val wm = windowManager ?: return

        // 1. 在初始化阶段预先计算出正确地悬浮窗起始尺寸，避免 WRAP_CONTENT 导致的首次显示闪烁或 320dp 限制
        val density = context.resources.displayMetrics.density
        val config = context.resources.configuration
        val screenWidthDp = config.screenWidthDp.toFloat()

        // 获取当前已有的同步状态
        val initialScale = repository.floatingScaleMultiplier.value
        val mirroredW = repository.mirroredWidth.value
        val mirroredH = repository.mirroredHeight.value

        // 依据纵横比计算初始像素宽高
        val aspectRatio = if (mirroredW > 0) mirroredH.toFloat() / mirroredW.toFloat() else 16f / 9f
        val initialWidthPx = (screenWidthDp * initialScale * density).toInt()
        val initialHeightPx = ((screenWidthDp * initialScale * aspectRatio + 24) * density).toInt()

        // 设定系统悬浮窗的核心布局配置参数
        val params = WindowManager.LayoutParams(
            initialWidthPx,
            initialHeightPx,
            // 兼容性适配：Android 8.0以上版本必须使用 TYPE_APPLICATION_OVERLAY 悬浮窗类型
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            // 设定无焦点属性 FLAG_NOT_FOCUSABLE，以及允许布局超出屏幕限制 (FLAG_LAYOUT_NO_LIMITS) 从而支持超大缩放
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 设定悬浮窗在桌面上的起步初始化偏移坐标
            x = 150
            y = 350
        }

        // 装载 Compose 环境并注入 ViewTree 关系
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(context)
            setViewTreeViewModelStoreOwner(context)
            setViewTreeSavedStateRegistryOwner(context)
        }

        composeView.setContent {
            val hasFrameReceived by repository.hasFrameReceived.collectAsState()
            val mirroredWidth by repository.mirroredWidth.collectAsState()
            val mirroredHeight by repository.mirroredHeight.collectAsState()

            // 1. 获取窗口信息（包含精确的像素尺寸）
            val windowInfo = LocalWindowInfo.current
            // 2. 获取当前的密度（用于像素转 DP）
            val density = LocalDensity.current

            // 3. 将像素宽度转换为精确的 DP 值
            val clientScreenWidthDp = with(density) {
                windowInfo.containerSize.width.toDp().value
            }

            // 订阅来自 Repository 的全局悬浮窗缩放比例
            val scaleMultiplier by repository.floatingScaleMultiplier.collectAsState()

            // 触摸锁定状态：为 true 时，支持双指手势缩放窗口；为 false 时，响应远程滑动/点击操作
            var isTouchLocked by remember { mutableStateOf(false) }

            // 依据远端画布分辨率自动计算宽高纵横比，实现完美的自适应缩放，且高度随宽度弹性拉伸
            val aspectRatio =
                if (mirroredWidth > 0) mirroredHeight.toFloat() / mirroredWidth.toFloat() else 16f / 9f
            val windowWidthDp = clientScreenWidthDp * scaleMultiplier
            val windowHeightDp = windowWidthDp * aspectRatio

            // 判定是否处于紧凑模式（宽度过小时隐藏次要按钮）
            val isCompact = windowWidthDp < 180

            // 强制将 Compose 计算出的 DP 尺寸同步给系统 WindowManager 的 LayoutParams
            LaunchedEffect(windowWidthDp, windowHeightDp) {
                val density = context.resources.displayMetrics.density
                val targetWidthPx = (windowWidthDp * density).toInt()
                val targetHeightPx = ((windowHeightDp + 24) * density).toInt()

                // 【优化】引入 2 像素阈值判断，减少由于浮点数微小变动导致的频繁系统调用
                if (kotlin.math.abs(params.width - targetWidthPx) > 2 ||
                    kotlin.math.abs(params.height - targetHeightPx) > 2
                ) {
                    params.width = targetWidthPx
                    params.height = targetHeightPx
                    try {
                        wm.updateViewLayout(composeView, params)
                    } catch (e: Exception) {
                        repository.addClientLog(
                            "更新悬浮窗尺寸失败: ${e.message}",
                            com.example.data.model.LogType.WARNING
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .border(2.dp, Color(0xFF6200EE), RoundedCornerShape(16.dp))
                    .background(Color.Black)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {

                    // 顶部操作手柄栏 (高24dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                            .background(Color(0xFF6200EE))
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    params.x += dragAmount.x.toInt()
                                    params.y += dragAmount.y.toInt()
                                    try {
                                        wm.updateViewLayout(composeView, params)
                                    } catch (e: Exception) {
                                        repository.addClientLog(
                                            "移动悬浮窗失败: ${e.message}",
                                            com.example.data.model.LogType.WARNING
                                        )
                                    }
                                }
                            }
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        // 1. 状态文字 (仅在非紧凑模式显示)
                        if (!isCompact) {
                            Text(
                                text = if (isTouchLocked) "缩放中: ${(scaleMultiplier * 100).toInt()}%" else "控屏中:${(scaleMultiplier * 100).toInt()}%",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                textAlign = TextAlign.Center
                            )

                            // 2. 返回按钮 (仅在非紧凑模式显示)

                            IconButton(
                                onClick = { lifecycleScope.launch { repository.sendCommand("BACK") } },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // 3. Home 按钮 (仅在非紧凑模式显示)
                        IconButton(
                            onClick = { lifecycleScope.launch { repository.sendCommand("HOME") } },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Home,
                                contentDescription = "Home",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // 4. 触摸锁定按钮 (必须显示)
                        IconButton(
                            onClick = { isTouchLocked = !isTouchLocked },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Lock",
                                tint = if (isTouchLocked) Color(0xFFFF5252) else Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // 5. 关闭按钮 (必须显示)
                        IconButton(
                            onClick = { stopSelf() },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // 画面显示与控制面板区域
                    Box(
                        modifier = Modifier
                            .fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (hasFrameReceived) {

                            // 【优化】移除冗余 key，将重置逻辑收拢到 VideoSurfaceViewer 内部
                            var localViewW by remember { mutableIntStateOf(1) }
                            var localViewH by remember { mutableIntStateOf(1) }

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    // 监听双指手势缩放画面（仅在触摸锁定状态下生效）
                                    .pointerInput(isTouchLocked) {
                                        if (isTouchLocked) {
                                            detectTransformGestures { _, _, zoom, _ ->
                                                val newScale =
                                                    (scaleMultiplier * zoom).coerceIn(
                                                        0.2f,
                                                        0.8f
                                                    )
                                                repository.updateFloatingScaleMultiplier(
                                                    newScale
                                                )
                                            }
                                        }
                                    }
                            )
                            {
                                VideoSurfaceViewer(
                                    repository = repository,
                                    onSurfaceCreatedPoke = {
                                        // 【核心唤醒逻辑】组件挂载后延迟捅一下 WindowManager，确保 SurfaceView 被系统激活
                                        try {
                                            wm.updateViewLayout(composeView, params)
                                        } catch (_: Exception) {
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .onSizeChanged { size ->
                                            // 记录并缓存当前悬浮窗真实的物理显示宽高
                                            localViewW = size.width
                                            localViewH = size.height
                                        }
                                        // 监听手势 (全手势支持 + 60FPS 滑动节流)
                                        .pointerInput(
                                            localViewW,
                                            localViewH,
                                            isTouchLocked
                                        ) {
                                            if (isTouchLocked || localViewW <= 0 || localViewH <= 0) return@pointerInput

                                            val viewW = localViewW.toFloat()
                                            val viewH = localViewH.toFloat()

                                            // 用于 60FPS 节流的变量 (1000ms / 60 ≈ 16ms)
                                            var lastMoveTime = 0L
                                            val frameInterval = 16L

                                            kotlinx.coroutines.coroutineScope {
                                                // 1. 处理 Tap, DoubleTap, LongPress, Press
                                                launch {
                                                    detectTapGestures(
                                                        onTap = { offset ->
                                                            lifecycleScope.launch {
                                                                repository.sendCommand("TAP:${offset.x / viewW},${offset.y / viewH}")
                                                            }
                                                        },
                                                        onDoubleTap = { offset ->
                                                            lifecycleScope.launch {
                                                                repository.sendCommand("DOUBLE_TAP:${offset.x / viewW},${offset.y / viewH}")
                                                            }
                                                        },
                                                        onLongPress = { offset ->
                                                            lifecycleScope.launch {
                                                                repository.sendCommand("LONG_PRESS:${offset.x / viewW},${offset.y / viewH}")
                                                            }
                                                        }
                                                    )
                                                }

                                                // 2. 处理 Drag (滑动) 并进行 60FPS 节流
                                                launch {
                                                    detectDragGestures(
                                                        onDragStart = { offset ->
                                                            lifecycleScope.launch {
                                                                repository.sendCommand("DOWN:${offset.x / viewW},${offset.y / viewH}")
                                                            }
                                                        },
                                                        onDragEnd = {
                                                            lifecycleScope.launch {
                                                                repository.sendCommand("UP:0,0")
                                                            }
                                                        },
                                                        onDragCancel = {
                                                            lifecycleScope.launch {
                                                                repository.sendCommand("UP:0,0")
                                                            }
                                                        },
                                                        onDrag = { change, _ ->
                                                            change.consume()
                                                            val currentTime =
                                                                System.currentTimeMillis()
                                                            // 60FPS 节流逻辑：如果距离上次发送不足 16ms，则跳过本次发送
                                                            if (currentTime - lastMoveTime >= frameInterval) {
                                                                val rx = change.position.x / viewW
                                                                val ry = change.position.y / viewH
                                                                lifecycleScope.launch {
                                                                    repository.sendCommand("MOVE:$rx,$ry")
                                                                }
                                                                lastMoveTime = currentTime
                                                            }
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                )

                                // 锁定状态下的半透明遮罩与手势提示 HUD
                                if (isTouchLocked) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Black.copy(alpha = 0.45f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(
                                                imageVector = Icons.Default.Lock,
                                                contentDescription = "Pinch to zoom mode active",
                                                tint = Color(0xFFFF5252),
                                                modifier = Modifier.size(24.dp)
                                            )
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = "触摸已锁定\n双指手势可缩放大小\n当前比例: ${(scaleMultiplier * 100).toInt()}%",
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                textAlign = TextAlign.Center,
                                                lineHeight = 14.sp
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "等待远端投屏...",
                                    color = Color.Gray,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        try {
            // 向屏幕添加全局 Overlay 悬浮视图层
            wm.addView(composeView, params)
            floatingView = composeView
            // 【细节改进】addView 成功后，将生命周期提升至 RESUMED，确保 Compose 动画和 API 正常工作
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating window view: ${e.message}")
            stopSelf()
        }
    }

    /**
     * 服务被销毁生命周期
     */
    override fun onDestroy() {
        _isRunning.value = false
        Log.i(TAG, "FloatingWindowService onDestroy")
        // 【细节改进】在移除视图前，将状态退回至 STARTED
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        floatingView?.let { view ->
            try {
                // 彻底释放全局 Overlay 视图，杜绝悬浮窗内存泄漏
                windowManager?.removeView(view)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing dynamic window view: ${e.message}")
            }
        }
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }
}

/**
 * 视频帧表面视图呈现组件 (VideoSurfaceViewer)
 * 通过封装底层 SurfaceView 并初始化 Android 独占的 MediaCodec H.264 硬件解码器，
 * 将接收自服务端的 H.264 编码切片帧流高速还原绘制到手机悬浮窗上。
 *
 * @param viewModel 被订阅的共控核心业务 ViewModel 实例 [LanRemoteViewModel]
 * @param onSurfaceCreatedPoke 唤醒回调，用于捅一下 WindowManager 激活 SurfaceView
 * @param modifier 界面样式修饰 Modifier
 */
@Composable
fun VideoSurfaceViewer(
    repository: RemoteControlRepository,
    onSurfaceCreatedPoke: () -> Unit,
    modifier: Modifier = Modifier
) {
    val videoWidth by repository.mirroredWidth.collectAsState()
    val videoHeight by repository.mirroredHeight.collectAsState()
    val connectionState by repository.clientConnectionState.collectAsState()

    // 【核心重构】合并所有敏感 Key（分辨率+连接状态）。任何一个变化都会重启解码器和唤醒逻辑。
    androidx.compose.runtime.key(videoWidth, videoHeight, connectionState) {
        val tag = FloatingWindowService.TAG
        Log.d(
            tag,
            "VideoSurfaceViewer: Key Block Re-entered (W=$videoWidth, H=$videoHeight, State=$connectionState)"
        )

        // 使用 decoderToken 配合 decoder 确保 LaunchedEffect 能精准感知重建
        var decoderToken by remember {
            Log.d(tag, "VideoSurfaceViewer: State Reset (New Decoder Token)")
            mutableIntStateOf(0)
        }
        var decoder by remember { mutableStateOf<MediaCodec?>(null) }

        // 当解码环境（Key）发生变化时，延迟执行唤醒逻辑
        LaunchedEffect(Unit) {
            Log.d(tag, "VideoSurfaceViewer: LaunchedEffect(Unit) - Poking WindowManager")
            delay(200.milliseconds)
            onSurfaceCreatedPoke()
        }

        LaunchedEffect(repository, decoderToken) {
            Log.d(
                tag,
                "VideoSurfaceViewer: LaunchedEffect(Decoder) - Starting Frame Flow (Token=$decoderToken)"
            )
            val codec = decoder ?: return@LaunchedEffect
            repository.videoFrames.collect { frame ->
                val bytes = frame.data
                val flags = frame.flags
                val pts = frame.pts
                try {
                    val inputIndex = codec.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        codec.getInputBuffer(inputIndex)?.let { inputBuffer ->
                            inputBuffer.clear()
                            inputBuffer.put(bytes)
                            codec.queueInputBuffer(inputIndex, 0, bytes.size, pts, flags)
                        }
                    }

                    val bufferInfo = MediaCodec.BufferInfo()
                    var outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                    while (outputIndex >= 0) {
                        codec.releaseOutputBuffer(outputIndex, true)
                        outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                    }
                } catch (e: Exception) {
                    repository.addClientLog(
                        "解码推流失败: ${e.message}",
                        com.example.data.model.LogType.WARNING
                    )
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
                                    MediaFormat.MIMETYPE_VIDEO_AVC,
                                    videoWidth,
                                    videoHeight
                                )
                                codec.configure(format, holder.surface, null, 0)
                                codec.start()

                                decoder = codec
                                decoderToken++ // 强制重启 LaunchedEffect
                            } catch (e: Exception) {
                                repository.addClientLog(
                                    "构建解码器失败: ${e.message}",
                                    com.example.data.model.LogType.WARNING
                                )
                            }
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            w: Int,
                            h: Int
                        ) {
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            val activeCodec = decoder
                            decoder = null
                            try {
                                activeCodec?.stop()
                                activeCodec?.release()
                            } catch (_: Exception) {
                            }
                        }
                    })
                }
            },
            modifier = modifier
        )
    }
}
