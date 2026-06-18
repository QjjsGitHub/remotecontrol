package com.example

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.network.ConnectionState
import com.example.ui.LanRemoteViewModel
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

import android.content.pm.PackageManager

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager

    // Launcher for requesting background notification permission
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.d("MainActivity", "Notification permission approved")
        } else {
            Log.w("MainActivity", "Notification permission denied")
        }
    }

    // Activity launcher for capturing authorization
    private val recordResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // Start the Screen Capture service with captured result token
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("DATA", result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            LanRemoteViewModel.instance?.addServerLog("媒体投影屏幕捕获授权成功，已启动后台捕获服务", com.example.ui.LogType.SUCCESS)
        } else {
            Toast.makeText(this, "录屏请求被拒绝，无法启动屏幕共控分享", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        
        // Request POST_NOTIFICATIONS on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            MyApplicationTheme {
                SimplifiedDashboardScreen(
                    onRequestScreenShare = {
                        val installIntent = mediaProjectionManager.createScreenCaptureIntent()
                        recordResultLauncher.launch(installIntent)
                    }
                )
            }
        }
    }
}

@Composable
fun SimplifiedDashboardScreen(
    onRequestScreenShare: () -> Unit
) {
    val viewModel: LanRemoteViewModel = viewModel()
    val context = LocalContext.current

    // State bindings
    val isServerRunning by viewModel.isServerRunning.collectAsState()
    val serverIp by viewModel.serverIp.collectAsState()
    val connectedClients by viewModel.connectedClients.collectAsState()
    val serverLogs by viewModel.serverLogs.collectAsState()

    val connectionState by viewModel.connectionState.collectAsState()
    val manualIpField by viewModel.manualIpField.collectAsState()
    val discoveredServers by viewModel.discoveredServers.collectAsState()
    val controllerLogs by viewModel.controllerLogs.collectAsState()

    val mirroredBitmap by viewModel.mirroredBitmap.collectAsState()
    val mirroredWidth by viewModel.mirroredWidth.collectAsState()
    val mirroredHeight by viewModel.mirroredHeight.collectAsState()

    var isAccessibilityEnabled by remember { mutableStateOf(false) }
    var activeLogTab by remember { mutableStateOf(0) } // 0 = Feedback/Server, 1 = Controller

    var isBgOverlayActive by remember { mutableStateOf(false) }

    // Periodically query the status of accessibility and floating window services
    LaunchedEffect(Unit) {
        while (true) {
            isAccessibilityEnabled = RemoteAccessibilityService.isServiceRunning()
            isBgOverlayActive = FloatingWindowService.isRunning
            delay(1500)
        }
    }

    // Automatically launch background floating window on connection to remote control
    LaunchedEffect(connectionState) {
        if (connectionState == ConnectionState.Connected) {
            if (Settings.canDrawOverlays(context)) {
                if (!FloatingWindowService.isRunning) {
                    val serviceIntent = Intent(context, FloatingWindowService::class.java)
                    context.startService(serviceIntent)
                    isBgOverlayActive = true
                }
            } else {
                Toast.makeText(context, "连接成功！请授予悬浮窗权限以便使用独立后台悬浮受控端", Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${context.packageName}")
                )
                context.startActivity(intent)
            }
        } else {
            // connection lost, we can stop the floating service if running
            if (FloatingWindowService.isRunning) {
                val serviceIntent = Intent(context, FloatingWindowService::class.java)
                context.stopService(serviceIntent)
                isBgOverlayActive = false
            }
        }
    }

    // 坐标与拖拽状态变量
    var floatX by remember { mutableStateOf(100f) }
    var floatY by remember { mutableStateOf(400f) }
    
    // 订阅全局悬浮窗的比例系数 (直接与 ViewModel 的状态进行双向订阅绑定，与后台悬浮窗保持完美同步)
    val scaleMultiplier by viewModel.floatingScaleMultiplier.collectAsState()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Main unified scrollable layout
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Elegant branding header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)
                                ),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Screen Share Assistant",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "屏幕共控助手",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "一键开启局域网画面直播与触控反控",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // ------------------ SECTION 1: SERVER CONTROL PANEL (服务端配置) ------------------
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isServerRunning) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    border = borderStrokeForServer(isServerRunning)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Server", tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "1. 服务端设置 (被控端)",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            // Server running status dot
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isServerRunning) Color(0xFF4CAF50).copy(alpha = 0.2f) else Color.Red.copy(alpha = 0.15f)
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = if (isServerRunning) "服务激活中" else "未开启",
                                    color = if (isServerRunning) Color(0xFF4CAF50) else Color.Red,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Text(
                            text = "激活服务端后，此手机的屏幕画面会被流式发送。两边均在同一Wi-Fi下时，客户端在悬浮窗内点击即可将动作映射返控回该系统。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )

                        // Server Toggler Master Button
                        Button(
                            onClick = {
                                if (isServerRunning) {
                                    viewModel.stopServer(context)
                                } else {
                                    viewModel.startServer(context)
                                    if (!com.example.ScreenCaptureService.isRunning) {
                                        onRequestScreenShare()
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("toggle_server_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isServerRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = if (isServerRunning) "🛑 关闭服务端共享" else "🚀 开启服务端通道 (等待接入)",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (isServerRunning) {
                            Spacer(modifier = Modifier.height(14.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Spacer(modifier = Modifier.height(14.dp))

                            // Server IP Reference Node
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("本机 IP 配对节点:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    shape = RoundedCornerShape(6.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                ) {
                                    Text(
                                        text = serverIp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 14.sp,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Sub-feature: Screen Projector Recorder Toggler
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(if (ScreenCaptureService.isRunning) Color(0xFF4CAF50) else Color.Red, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("后台截屏直播服务", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                    Text(
                                        text = if (ScreenCaptureService.isRunning) "捕获引擎就绪，投屏广播中" else "需要启动媒体录屏授权",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Button(
                                    onClick = {
                                        if (ScreenCaptureService.isRunning) {
                                            context.stopService(Intent(context, ScreenCaptureService::class.java))
                                            viewModel.addServerLog("主动停止了屏幕捕捉进程", com.example.ui.LogType.WARNING)
                                        } else {
                                            onRequestScreenShare()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (ScreenCaptureService.isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    Text(if (ScreenCaptureService.isRunning) "停投" else "启投", fontSize = 12.sp)
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Sub-feature: Accessibility simulation Injector Status
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(if (isAccessibilityEnabled) Color(0xFF4CAF50) else Color.Red, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("全局仿真触控无障碍手势", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                    Text(
                                        text = if (isAccessibilityEnabled) "无障碍勾住激活，接收动作时可模拟点击" else "未授权，无法代理模拟动作映射",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Button(
                                    onClick = {
                                        try {
                                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                            }
                                            context.startActivity(intent)
                                            Toast.makeText(context, "请在系统页面找到「屏幕共控」服务并启用权限", Toast.LENGTH_LONG).show()
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "跳转失败，请到系统设置开启无障碍", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    Text("配置授权", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                // ------------------ SECTION 2: CLIENT CONTROL PANEL (客户端配置) ------------------
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (connectionState == ConnectionState.Connected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    border = borderStrokeForClient(connectionState)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Phone, contentDescription = "Client", tint = MaterialTheme.colorScheme.secondary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "2. 客户端设置 (控制端)",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            // Client state badge
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = when (connectionState) {
                                        ConnectionState.Connected -> Color(0xFF4CAF50).copy(alpha = 0.2f)
                                        ConnectionState.Connecting -> Color(0xFFFFA500).copy(alpha = 0.15f)
                                        ConnectionState.Disconnected -> Color.Red.copy(alpha = 0.15f)
                                    }
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = when (connectionState) {
                                        ConnectionState.Connected -> "已连通"
                                        ConnectionState.Connecting -> "接入中"
                                        ConnectionState.Disconnected -> "未连接"
                                    },
                                    color = when (connectionState) {
                                        ConnectionState.Connected -> Color(0xFF4CAF50)
                                        ConnectionState.Connecting -> Color(0xFFFFA500)
                                        ConnectionState.Disconnected -> Color.Red
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Text(
                            text = "在此模块中输入局域网内其他服务端的 IP 地址打通配对。连入后屏幕上将弹出拖拽浮动投屏窗，可以无缝监控、点击或拖动模拟反控目标机器。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )

                        // LAN Discovered Devices Chip list
                        if (discoveredServers.isNotEmpty()) {
                            Text(
                                "发现同Wi-Fi网络服务端 (点击一键填入):",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                discoveredServers.forEach { ip ->
                                    SuggestionChip(
                                        onClick = { viewModel.selectServer(ip) },
                                        label = { Text(ip, fontFamily = FontFamily.Monospace) }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        // IP input and Connect button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = manualIpField,
                                onValueChange = { viewModel.setManualIp(it) },
                                label = { Text("服务端核心 IP 节点") },
                                placeholder = { Text("例如 192.168.1.100") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("ip_input"),
                                shape = RoundedCornerShape(12.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            if (connectionState == ConnectionState.Connected) {
                                Button(
                                    onClick = { viewModel.disconnectFromServer() },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                    modifier = Modifier.height(56.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("切断")
                                }
                            } else {
                                Button(
                                    onClick = { viewModel.connectToSelectedServer(context) },
                                    modifier = Modifier.height(56.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("配对连接")
                                }
                            }
                        }

                        // 悬浮窗高级参数配置子面板 (仅在建立连接并成功接收捕获帧时显示)
                        if (connectionState == ConnectionState.Connected && mirroredBitmap != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                "📐 悬浮窗口尺寸比例调配",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "滑动改变远端在大屏悬浮窗里的自适应微缩尺寸，可精准调节大小避免遮挡本地信息:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            // 基于当前设备真实 DP 宽度来作自适应换算，保证任何分辨率密度的手机上显示比例的一致性
                            val configuration = LocalConfiguration.current
                            val clientScreenWidthDp = configuration.screenWidthDp.toFloat()
                            // 依据远端画面纵横比自适应高度
                            val aspectRatio = if (mirroredWidth > 0) mirroredHeight.toFloat() / mirroredWidth.toFloat() else 16f / 9f
                            // 默认尺寸标准是占屏幕宽度的 1/3 (即这里的 clientScreenWidthDp / 3f)，再乘以 Slider 传递出的缩放系数
                            val currentWidthDp = (clientScreenWidthDp / 3f) * scaleMultiplier
                            val currentHeightDp = currentWidthDp * aspectRatio

                            Slider(
                                value = scaleMultiplier,
                                onValueChange = { viewModel.updateFloatingScaleMultiplier(it) },
                                valueRange = 0.5f..2.5f,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = "预计尺寸: ${currentWidthDp.roundToInt()}dp x ${currentHeightDp.roundToInt()}dp (缩放率: ${(scaleMultiplier * 100).toInt()}%)",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.secondary,
                                textAlign = TextAlign.End,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(16.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                "📱 后台独立全局悬浮窗",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "开启后，在返回桌面时将以默认 1/3 屏幕宽度自适应悬浮显示对端画面。在悬浮窗上锁定触摸后支持双指手势缩放，解锁时支持反向遥控操作:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Button(
                                onClick = {
                                    if (isBgOverlayActive) {
                                        val serviceIntent = Intent(context, FloatingWindowService::class.java)
                                        context.stopService(serviceIntent)
                                        isBgOverlayActive = false
                                    } else {
                                        if (!android.provider.Settings.canDrawOverlays(context)) {
                                            Toast.makeText(context, "请先授予：显示在其他应用上层 (悬浮窗权限)", Toast.LENGTH_LONG).show()
                                            val intent = Intent(
                                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                android.net.Uri.parse("package:${context.packageName}")
                                            )
                                            context.startActivity(intent)
                                        } else {
                                            val serviceIntent = Intent(context, FloatingWindowService::class.java)
                                            context.startService(serviceIntent)
                                            isBgOverlayActive = true
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isBgOverlayActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                ),
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = if (isBgOverlayActive) androidx.compose.material.icons.Icons.Default.Close else androidx.compose.material.icons.Icons.Default.Share,
                                        contentDescription = "Background Floating Window Toggle",
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(if (isBgOverlayActive) "关闭后台悬浮窗" else "开启后台显示自适应悬浮窗")
                                }
                            }
                        }
                    }
                }

                // ------------------ SECTION 3: SYSTEM CONSOLE EVENT LOGS (事件流反馈日志) ------------------
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Tab Selector
                            Row(
                                modifier = Modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp)).padding(2.dp)
                            ) {
                                Text(
                                    text = "服务端事件",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (activeLogTab == 0) MaterialTheme.colorScheme.primary else Color.Gray,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (activeLogTab == 0) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                        .clickable { activeLogTab = 0 }
                                        .padding(horizontal = 14.dp, vertical = 6.dp)
                                )
                                Text(
                                    text = "客户端事件流",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (activeLogTab == 1) MaterialTheme.colorScheme.secondary else Color.Gray,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (activeLogTab == 1) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                        .clickable { activeLogTab = 1 }
                                        .padding(horizontal = 14.dp, vertical = 6.dp)
                                )
                            }

                            // Clear logs
                            TextButton(
                                onClick = {
                                    if (activeLogTab == 0) viewModel.clearServerLogs() else viewModel.clearControllerLogs()
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("清空日志", fontSize = 11.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Box holding the tab contents
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (activeLogTab == 0) {
                                // Server Logs
                                if (serverLogs.isEmpty()) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text("无服务端交互反馈事件...", color = MaterialTheme.colorScheme.outline, fontSize = 12.sp)
                                    }
                                } else {
                                    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        items(serverLogs) { log ->
                                            Row {
                                                Text("[${log.timestamp}] ", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                                                val col = when (log.type) {
                                                    com.example.ui.LogType.SUCCESS -> Color(0xFF4CAF50)
                                                    com.example.ui.LogType.WARNING -> Color(0xFFFF9800)
                                                    com.example.ui.LogType.INFO -> MaterialTheme.colorScheme.onSurface
                                                }
                                                Text(log.message, fontSize = 11.sp, color = col, fontWeight = FontWeight.Medium)
                                            }
                                        }
                                    }
                                }
                            } else {
                                // Client Logs
                                if (controllerLogs.isEmpty()) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text("无客户端操作日志...", color = MaterialTheme.colorScheme.outline, fontSize = 12.sp)
                                    }
                                } else {
                                    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        items(controllerLogs) { log ->
                                            Row {
                                                Text("[${log.timestamp}] ", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                                                val col = when (log.type) {
                                                    com.example.ui.LogType.SUCCESS -> Color(0xFF4CAF50)
                                                    com.example.ui.LogType.WARNING -> Color(0xFFFF9800)
                                                    com.example.ui.LogType.INFO -> MaterialTheme.colorScheme.onSurface
                                                }
                                                Text(log.message, fontSize = 11.sp, color = col, fontWeight = FontWeight.Medium)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ------------------ INTERACTIVE FLOATING PICTURE-IN-PICTURE (PIP) SCREEN MIRROR OVERLAY ------------------
            // Disabled in-app screen mirror to use background floating window exclusively as user requested
            val activeBitmap = mirroredBitmap
            if (false && connectionState == ConnectionState.Connected) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(floatX.roundToInt(), floatY.roundToInt()) }
                        .width((mirroredWidth * scaleMultiplier).dp)
                        .height(((mirroredHeight * scaleMultiplier) + 36).dp) // +36dp for header
                        .clip(RoundedCornerShape(16.dp))
                        .border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                        .background(Color.Black)
                        .testTag("floating_mirror_pip")
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Title header bar (Double drag gesture target)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                                .background(MaterialTheme.colorScheme.primary)
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        floatX += dragAmount.x
                                        floatY += dragAmount.y
                                    }
                                }
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Menu, contentDescription = "Drag Window", tint = Color.White, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("远端流式画面", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Text("点击即可反控 🔗", color = Color.White.copy(alpha = 0.85f), fontSize = 9.sp, fontWeight = FontWeight.Medium)
                        }

                        // Live Canvas Frame with Action Event Handlers
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            if (activeBitmap != null) {
                                var localFrameW by remember { mutableStateOf(1) }
                                var localFrameH by remember { mutableStateOf(1) }

                                Image(
                                    bitmap = activeBitmap.asImageBitmap(),
                                    contentDescription = "Active live frame stream from server",
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .onSizeChanged { size ->
                                            localFrameW = size.width
                                            localFrameH = size.height
                                        }
                                        .pointerInput(localFrameW, localFrameH) {
                                            detectTapGestures(
                                                onTap = { offset ->
                                                    if (localFrameW > 0 && localFrameH > 0) {
                                                        val rx = offset.x / localFrameW
                                                        val ry = offset.y / localFrameH
                                                        viewModel.sendClientAction("TAP:$rx,$ry")
                                                    }
                                                }
                                            )
                                        }
                                        .pointerInput(localFrameW, localFrameH) {
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
                                                    if (localFrameW > 0 && localFrameH > 0) {
                                                        val distSq = (currentX - dragStartX) * (currentX - dragStartX) + (currentY - dragStartY) * (currentY - dragStartY)
                                                        if (distSq > 400) { // Mapping swipe action
                                                            val rsx = dragStartX / localFrameW
                                                            val rsy = dragStartY / localFrameH
                                                            val rex = currentX / localFrameW
                                                            val rey = currentY / localFrameH
                                                            viewModel.sendClientAction("SWIPE:$rsx,$rsy;$rex,$rey")
                                                            
                                                            // continuous loop
                                                            dragStartX = currentX
                                                            dragStartY = currentY
                                                        }
                                                    }
                                                }
                                            )
                                        },
                                    contentScale = ContentScale.Fit
                                )
                            } else {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                    modifier = Modifier.padding(16.dp)
                                ) {
                                    CircularProgressIndicator(
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        "连接成功 🔗",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        "等待服务端开启录屏...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.7f),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun borderStrokeForServer(active: Boolean): BorderStroke {
    return if (active) {
         BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
         BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
fun borderStrokeForClient(conState: ConnectionState): BorderStroke {
    return if (conState == ConnectionState.Connected) {
         BorderStroke(2.dp, MaterialTheme.colorScheme.secondary)
    } else {
         BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }
}
