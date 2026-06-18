package com.example

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.ui.LanRemoteViewModel
import com.example.network.ConnectionState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

/**
 * 后台安全全局悬浮窗控制服务 (FloatingWindowService)
 * 该服务允许在不处于前台 activity 的情况下，常驻在系统桌面之上显示 1/2 比例的受控画面，
 * 并支持实时的 TAP 与 SWIPE 反向遥控手势操作注入。
 */
class FloatingWindowService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

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
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.STARTED

        // 绑定窗口管理员并创建浮空视窗
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
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

        // 设定系统悬浮窗的核心布局配置参数
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            // 兼容性适配：Android 8.0以上版本必须使用 TYPE_APPLICATION_OVERLAY 悬浮窗类型
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            // 设定无焦点属性 FLAG_NOT_FOCUSABLE (不拦截后面桌面的输入法，并保证悬浮窗可点击)，以及在屏内布局
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
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
                val mirroredBitmap by viewModel.mirroredBitmap.collectAsState()
                val mirroredWidth by viewModel.mirroredWidth.collectAsState()
                val mirroredHeight by viewModel.mirroredHeight.collectAsState()

                // 默认比例1/3 (0.33f)，且由 mutableState 管理，支持双指进行动态手势缩放窗口
                var scaleMultiplier by remember { mutableStateOf(0.33f) }
                // 触摸锁定状态：为 true 时，支持双指手势缩放窗口；为 false 时，响应远程滑动/点击操作
                var isTouchLocked by remember { mutableStateOf(false) }

                Box(
                    modifier = Modifier
                        .width((mirroredWidth * scaleMultiplier).dp)
                        .height(((mirroredHeight * scaleMultiplier) + 36).dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(3.dp, Color(0xFF6200EE), RoundedCornerShape(16.dp))
                        .background(Color.Black)
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        
                        // 顶部操作手柄栏 (高36dp) — 拦截并响应物理手势，使整个悬浮窗在系统桌面上无缝拖拽移动
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp)
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
                                        tint = if (isTouchLocked) Color(0xFFFF5252) else Color.White.copy(alpha = 0.6f),
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
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            val activeBitmap = mirroredBitmap
                            if (activeBitmap != null) {
                                var localViewW by remember { mutableStateOf(1) }
                                var localViewH by remember { mutableStateOf(1) }

                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        // 监听双指手势缩放画面 (仅在锁定状态下生效)
                                        .pointerInput(isTouchLocked) {
                                            if (isTouchLocked) {
                                                detectTransformGestures { _, _, zoom, _ ->
                                                    scaleMultiplier = (scaleMultiplier * zoom).coerceIn(0.15f, 1.20f)
                                                }
                                            }
                                        }
                                ) {
                                    Image(
                                        bitmap = activeBitmap.asImageBitmap(),
                                        contentDescription = "Background live screen stream",
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .onSizeChanged { size ->
                                                // 记录并缓存当前悬浮窗真实的物理显示宽高
                                                localViewW = size.width
                                                localViewH = size.height
                                            }
                                            // 监听点击手势 (仅在解锁/未锁定状态下响应反向控制)
                                            .pointerInput(localViewW, localViewH, isTouchLocked) {
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
                                            .pointerInput(localViewW, localViewH, isTouchLocked) {
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
                                                                val rsx = dragStartX / localViewW
                                                                val rsy = dragStartY / localViewH
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
