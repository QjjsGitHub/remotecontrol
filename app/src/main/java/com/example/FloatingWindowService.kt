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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
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
import androidx.compose.ui.node.Ref
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
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
import com.example.network.ConnectionState
import com.example.ui.LanRemoteViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * 后台安全全局悬浮窗控制服务 (FloatingWindowService)
 * 该服务允许在不处于前台 activity 的情况下，常驻在系统桌面之上显示 1/2 比例的受控画面，
 * 并支持实时的 TAP 与 SWIPE 反向遥控手势操作注入。
 */
class FloatingWindowService : Service(), LifecycleOwner, ViewModelStoreOwner,
    SavedStateRegistryOwner {

    companion object {
        const val TAG = "FloatingWindowService"

        /**
         * 标记后台悬浮窗服务当前是否正在运行
         */
        var isRunning = false
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
        isRunning = true
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
            LanRemoteViewModel.instance?.connectionState?.collectLatest { state ->
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
        val viewModel = LanRemoteViewModel.instance

        // 1. 在初始化阶段预先计算出正确地悬浮窗起始尺寸，避免 WRAP_CONTENT 导致的首次显示闪烁或 320dp 限制
        val density = context.resources.displayMetrics.density
        val config = context.resources.configuration
        val screenWidthDp = config.screenWidthDp.toFloat()

        // 获取当前已有的同步状态
        val initialScale = viewModel?.floatingScaleMultiplier?.value ?: 0.3f
        val mirroredW = viewModel?.mirroredWidth?.value ?: 1080
        val mirroredH = viewModel?.mirroredHeight?.value ?: 2400

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
            val viewModel = LanRemoteViewModel.instance
            if (viewModel != null) {
                val hasFrameReceived by viewModel.hasFrameReceived.collectAsState()
                val mirroredWidth by viewModel.mirroredWidth.collectAsState()
                val mirroredHeight by viewModel.mirroredHeight.collectAsState()

                // 获取客户端当前屏幕配置，用于自适应基准宽度计算
                val configuration = LocalConfiguration.current
                val clientScreenWidthDp = configuration.screenWidthDp.toFloat()

                // 订阅来自 ViewModel 的全局悬浮窗缩放比例 (双向绑定，与主界面 Slider 同步)
                val scaleMultiplier by viewModel.floatingScaleMultiplier.collectAsState()

                // 触摸锁定状态：为 true 时，支持双指手势缩放窗口；为 false 时，响应远程滑动/点击操作
                var isTouchLocked by remember { mutableStateOf(false) }

                // 依据远端画布分辨率自动计算宽高纵横比，实现完美的自适应缩放，且高度随宽度弹性拉伸
                val aspectRatio =
                    if (mirroredWidth > 0) mirroredHeight.toFloat() / mirroredWidth.toFloat() else 16f / 9f
                val windowWidthDp = clientScreenWidthDp * scaleMultiplier
                val windowHeightDp = windowWidthDp * aspectRatio

                // 强制将 Compose 计算出的 DP 尺寸同步给系统 WindowManager 的 LayoutParams，
                // 从而绕过 WRAP_CONTENT 可能存在的 320dp 或屏幕边界测量限制。
                LaunchedEffect(windowWidthDp, windowHeightDp) {
                    val density = context.resources.displayMetrics.density
                    val targetWidthPx = (windowWidthDp * density).toInt()
                    val targetHeightPx = ((windowHeightDp + 24) * density).toInt()

                    // 只有当目标像素尺寸与当前 WindowManager Params 中的尺寸不符时，才触发更新
                    if (params.width != targetWidthPx || params.height != targetHeightPx) {
                        params.width = targetWidthPx
                        params.height = targetHeightPx
                        try {
                            wm.updateViewLayout(composeView, params)
                            Log.d(
                                TAG,
                                "WindowManager layout updated to: ${params.width}x${params.height}"
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Update window layout failed: ${e.message}")
                        }
                    } else {
                        Log.d(TAG, "Dimensions unchanged, skipping redundant updateViewLayout")
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

                        // 顶部操作手柄栏 (高36dp) — 拦截并响应物理手势，使整个悬浮窗在系统桌面上无缝拖拽移动
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(24.dp)
                                .background(Color(0xFF6200EE))
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        // 累加计算拖动坐标并通知 WindowManager 更新布局
                                        params.x += dragAmount.x.toInt()
                                        params.y += dragAmount.y.toInt()
                                        try {
                                            wm.updateViewLayout(composeView, params)
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Error dragging window: ${e.message}")
                                        }
                                    }
                                }
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Menu,
                                    contentDescription = "Drag Window",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isTouchLocked) "尺缩调节中 (${(scaleMultiplier * 100).toInt()}%)" else "后台控屏中 (${(scaleMultiplier * 100).toInt()}%)",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.End
                            ) {
                                // 触摸锁定/解锁按钮：锁定状态支持对端大小缩放，解锁状态支持控制下发
                                IconButton(
                                    onClick = { isTouchLocked = !isTouchLocked },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = "Toggle Touch Lock for Pinch Sizing",
                                        tint = if (isTouchLocked) Color(0xFFFF5252) else Color.White.copy(
                                            alpha = 0.6f
                                        ),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))

                                // 独立关闭小按钮
                                IconButton(
                                    onClick = { stopSelf() },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close Floating Window",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        // 画面显示与控制面板区域
                        Box(
                            modifier = Modifier
                                .fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            val hasFrame = hasFrameReceived
                            if (hasFrame) {
                                var localViewW by remember { mutableIntStateOf(1) }
                                var localViewH by remember { mutableIntStateOf(1) }

                                //key(videoWidth, videoHeight) {

                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        // 监听双指手势缩放画面（仅在触摸锁定状态下生效，并更新全局 ViewModel 比例使与 Slider 同步）
                                        .pointerInput(isTouchLocked) {
                                            if (isTouchLocked) {
                                                detectTransformGestures { _, _, zoom, _ ->
                                                    val newScale =
                                                        (scaleMultiplier * zoom).coerceIn(
                                                            0.2f,
                                                            0.8f
                                                        )
                                                    viewModel.updateFloatingScaleMultiplier(
                                                        newScale
                                                    )
                                                }
                                            }
                                        }
                                )
                                {
                                    VideoSurfaceViewer(
                                        viewModel = viewModel,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .onSizeChanged { size ->
                                                // 记录并缓存当前悬浮窗真实的物理显示宽高
                                                localViewW = size.width
                                                localViewH = size.height
                                            }
                                            // 监听点击手势 (仅在解锁/未锁定状态下响应反向控制)
                                            .pointerInput(
                                                localViewW,
                                                localViewH,
                                                isTouchLocked
                                            ) {
                                                if (!isTouchLocked) {
                                                    detectTapGestures(
                                                        onTap = { offset ->
                                                            if (localViewW > 0 && localViewH > 0) {
                                                                val rx = offset.x / localViewW
                                                                val ry = offset.y / localViewH
                                                                viewModel.sendClientAction("TAP:$rx,$ry")
                                                            }
                                                        }
                                                    )
                                                }
                                            }
                                            // 监听滑动划扫手势 (仅在解锁/未锁定状态下响应反向控制)
                                            .pointerInput(
                                                localViewW,
                                                localViewH,
                                                isTouchLocked
                                            ) {
                                                if (!isTouchLocked) {
                                                    var dragStartX = 0f
                                                    var dragStartY = 0f
                                                    detectDragGestures(
                                                        onDragStart = { offset ->
                                                            dragStartX = offset.x
                                                            dragStartY = offset.y
                                                        },
                                                        onDragEnd = {},
                                                        onDrag = { change, dragAmount ->
                                                            change.consume()
                                                            val currentX = change.position.x
                                                            val currentY = change.position.y
                                                            if (localViewW > 0 && localViewH > 0) {
                                                                val rsx =
                                                                    dragStartX / localViewW
                                                                val rsy =
                                                                    dragStartY / localViewH
                                                                val rex = currentX / localViewW
                                                                val rey = currentY / localViewH
                                                                viewModel.sendClientAction("SWIPE:$rsx,$rsy;$rex,$rey")
                                                            }
                                                        }
                                                    )
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

                                //}

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
            } else {
                Box(
                    modifier = Modifier
                        .size(150.dp)
                        .background(Color.Black, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("未连接服务", color = Color.White, fontSize = 12.sp)
                }
            }
        }

        try {
            // 向屏幕添加全局 Overlay 悬浮视图层
            wm.addView(composeView, params)
            floatingView = composeView
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating window view: ${e.message}")
            stopSelf()
        }
    }

    /**
     * 服务被销毁生命周期
     */
    override fun onDestroy() {
        isRunning = false
        Log.i(TAG, "FloatingWindowService onDestroy")
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
 * @param modifier 界面样式修饰 Modifier
 */
@Composable
fun VideoSurfaceViewer(
    viewModel: LanRemoteViewModel,
    modifier: Modifier = Modifier
) {
    val videoWidth by viewModel.mirroredWidth.collectAsState()
    val videoHeight by viewModel.mirroredHeight.collectAsState()
    val decoderRef = remember { Ref<MediaCodec>() }

    LaunchedEffect(viewModel) {
        viewModel.encodedFrameFlow.collect { frameInfo ->
            var codec = decoderRef.value
            while (codec == null) {
                delay(50.milliseconds)
                codec = decoderRef.value
            }
            val bytes = frameInfo.first
            val flags = frameInfo.second
            val pts = frameInfo.third
            try {
                val inputIndex = codec.dequeueInputBuffer(10000)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                    if (inputBuffer != null) {
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
                Log.e("VideoSurfaceViewer", "视频硬解码切片输入排队失败或数据损毁: ${e.message}")
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
                            decoderRef.value = codec

                            // 首次建立 Surface 时，立即注入此前本地缓存的 SPS/PPS 配置帧 (flags=2)，初始化解码参数以避免画面加载首帧发黑
                            viewModel.clientCodecConfig.value?.let { configFrame ->
                                try {
                                    val inputIndex = codec.dequeueInputBuffer(10000)
                                    if (inputIndex >= 0) {
                                        val inputBuffer = codec.getInputBuffer(inputIndex)
                                        if (inputBuffer != null) {
                                            inputBuffer.clear()
                                            inputBuffer.put(configFrame.first)
                                            codec.queueInputBuffer(
                                                inputIndex,
                                                0,
                                                configFrame.first.size,
                                                configFrame.third,
                                                configFrame.second
                                            )
                                            Log.d(
                                                "VideoSurfaceViewer",
                                                "解码器启动时已成功自动填充 SPS/PPS 缓存配置帧, 大小: ${configFrame.first.size}"
                                            )
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(
                                        "VideoSurfaceViewer",
                                        "启动注入 SPS/PPS 缓存特征包失败: ${e.message}"
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(
                                "VideoSurfaceViewer",
                                "构建/配置 H.264 解码器实例时报错崩溃: ${e.message}"
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
                        try {
                            decoderRef.value?.stop()
                            decoderRef.value?.release()
                        } catch (e: Exception) {
                            Log.e(
                                "VideoSurfaceViewer",
                                "销毁解码器或回收物理内存失败: ${e.message}"
                            )
                        }
                        decoderRef.value = null
                    }
                })
            }
        },
        modifier = modifier
    )
}
