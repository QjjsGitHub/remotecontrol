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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
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

class FloatingWindowService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    companion object {
         const val TAG = "FloatingWindowService"
         var isRunning = false
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
        isRunning = true
        Log.i(TAG, "FloatingWindowService onCreate")
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.STARTED

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        setupFloatingWindow()

        // Monitor remote connection - if disconnected, automatically stop service
        lifecycleScope.launch {
            LanRemoteViewModel.instance?.connectionState?.collectLatest { state ->
                if (state == ConnectionState.Disconnected) {
                    Log.i(TAG, "Connection lost, closing floating window")
                    stopSelf()
                }
            }
        }
    }

    private fun setupFloatingWindow() {
        val context = this
        val wm = windowManager ?: return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 150
            y = 350
        }

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

                // Default ratio is 1/2 of original size, maintaining perfect picture aspect ratio
                val scaleMultiplier = 0.50f

                Box(
                    modifier = Modifier
                        .width((mirroredWidth * scaleMultiplier).dp)
                        .height(((mirroredHeight * scaleMultiplier) + 36).dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(3.dp, Color(0xFF6200EE), RoundedCornerShape(16.dp))
                        .background(Color.Black)
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Title bar - handles dragging globally on screen
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                                .background(Color(0xFF6200EE))
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Menu,
                                    contentDescription = "Drag Window",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "后台共控中 (1/2 比例)",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

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

                        // Mirrored stream visualizer with gesture injectors
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

                                Image(
                                    bitmap = activeBitmap.asImageBitmap(),
                                    contentDescription = "Background live screen stream",
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .onSizeChanged { size ->
                                            localViewW = size.width
                                            localViewH = size.height
                                        }
                                        .pointerInput(localViewW, localViewH) {
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
                                        .pointerInput(localViewW, localViewH) {
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
                                )
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
            wm.addView(composeView, params)
            floatingView = composeView
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating window view: ${e.message}")
            stopSelf()
        }
    }

    override fun onDestroy() {
        isRunning = false
        Log.i(TAG, "FloatingWindowService onDestroy")
        floatingView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing dynamic window view: ${e.message}")
            }
        }
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }
}
