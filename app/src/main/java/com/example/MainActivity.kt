package com.example

import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.network.ConnectionState
import com.example.ui.LanRemoteViewModel
import com.example.ui.theme.MyApplicationTheme
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager

    // 用于在 Android 13+ 系统上动态请求推送通知权限的启动器
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.d("MainActivity", "通知权限已批准")
        } else {
            Log.w("MainActivity", "通知权限被拒绝")
        }
    }

    // 用于发起并接收系统高级屏幕截图许可的 Activity 启动器
    private val recordResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            // 在用户授权捕获屏幕后，将相关 token 数据下发至 ScreenCaptureService 并启动前台录屏进程
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("DATA", result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            LanRemoteViewModel.instance?.addServerLog(
                "媒体投影屏幕捕获授权成功，已启动后台捕获服务",
                com.example.ui.LogType.SUCCESS
            )
        } else {
            Toast.makeText(this, "录屏请求被拒绝，无法启动屏幕共控分享", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // 如果运行在 Android 13 (Tiramisu) 级别以上的系统，检查并动态请求 POST_NOTIFICATIONS 推送通知权限
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

/**
 * 屏幕共控助手极简配置主看板面 (SimplifiedDashboardScreen)
 *
 * @param onRequestScreenShare 请求拉起系统流式投屏录制组件授权的回调方法
 */
@Composable
fun SimplifiedDashboardScreen(
    onRequestScreenShare: () -> Unit
) {
    val viewModel: LanRemoteViewModel = viewModel()
    val context = LocalContext.current

    // 数据状态流的双向绑定订阅
    val isServerRunning by viewModel.isServerRunning.collectAsState()
    val serverIp by viewModel.serverIp.collectAsState()
    val serverLogs by viewModel.serverLogs.collectAsState()

    val connectionState by viewModel.connectionState.collectAsState()
    val manualIpField by viewModel.manualIpField.collectAsState()
    val discoveredServers by viewModel.discoveredServers.collectAsState()
    val controllerLogs by viewModel.controllerLogs.collectAsState()

    val hasFrameReceived by viewModel.hasFrameReceived.collectAsState()
    val mirroredWidth by viewModel.mirroredWidth.collectAsState()
    val mirroredHeight by viewModel.mirroredHeight.collectAsState()

    // 订阅全局后台服务的实时运行状态，实现事件驱动的 UI 自动刷新
    val isFloatingWindowRunning by FloatingWindowService.isRunning.collectAsStateWithLifecycle()
    val isAccessibilityRunning by RemoteAccessibilityService.isRunning.collectAsStateWithLifecycle()

    var activeLogTab by remember { mutableIntStateOf(0) } // 0 = 服务端日志, 1 = 客户端日志


    // 监听连接状态的变化：一旦连接打通，检查并在此被控设备大盘上自动发起并加载后台常驻桌面悬浮窗
    LaunchedEffect(connectionState) {
        if (connectionState == ConnectionState.Connected) {
            if (Settings.canDrawOverlays(context)) {
                if (!FloatingWindowService.isRunning.value) {
                    val serviceIntent = Intent(context, FloatingWindowService::class.java)
                    context.startService(serviceIntent)
                }
            } else {
                Toast.makeText(
                    context,
                    "连接成功！请授予悬浮窗权限以便使用独立后台悬浮受控端",
                    Toast.LENGTH_LONG
                ).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    "package:${context.packageName}".toUri()
                )
                context.startActivity(intent)
            }
        } else {
            // 通道意外中断，或人为主动切断连接后，彻底释放并注销大屏悬浮窗
            if (FloatingWindowService.isRunning.value) {
                val serviceIntent = Intent(context, FloatingWindowService::class.java)
                context.stopService(serviceIntent)
            }
        }
    }

    // 订阅全局悬浮窗的比例系数 (直接与 ViewModel 的状态进行双向订阅绑定，与后台悬浮窗保持完美同步)
    val scaleMultiplier by viewModel.floatingScaleMultiplier.collectAsState()

    // 订阅全局屏幕捕捉前台服务的活动状态 (实时更新指示灯与开关)
    val isScreenCaptureRunning by ScreenCaptureService.isServiceRunning.collectAsState()

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
            // 采用了一站式带安全间距的滚动渲染布局容器
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 配色酷炫优雅的 App 招牌栏 (Elegant branding header)
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
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.colorScheme.secondary
                                    )
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
                        containerColor = if (isServerRunning) MaterialTheme.colorScheme.primaryContainer.copy(
                            alpha = 0.25f
                        )
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
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = "Server",
                                    tint = MaterialTheme.colorScheme.primary
                                )
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
                                    containerColor = if (isServerRunning) Color(0xFF4CAF50).copy(
                                        alpha = 0.2f
                                    ) else Color.Red.copy(alpha = 0.15f)
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

                        // 本地共享服务端主开关按钮
                        Button(
                            onClick = {
                                if (isServerRunning) {
                                    viewModel.stopServer(context)
                                } else {
                                    viewModel.startServer(context)
                                    if (!isScreenCaptureRunning) {
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
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(
                                    alpha = 0.5f
                                )
                            )
                            Spacer(modifier = Modifier.height(14.dp))

                            // Server IP Reference Node
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "本机 IP 配对节点:",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    shape = RoundedCornerShape(6.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        MaterialTheme.colorScheme.outlineVariant
                                    )
                                ) {
                                    Text(
                                        text = serverIp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 14.sp,
                                        modifier = Modifier.padding(
                                            horizontal = 10.dp,
                                            vertical = 4.dp
                                        )
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // 子功能：后台截屏投屏直播服务控制卡片
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(
                                            if (isScreenCaptureRunning) Color(0xFF4CAF50) else Color.Red,
                                            CircleShape
                                        )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "后台截屏直播服务",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = if (isScreenCaptureRunning) "捕获引擎就绪，投屏广播中" else "需要启动媒体录屏授权",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Button(
                                    onClick = {
                                        if (isScreenCaptureRunning) {
                                            context.stopService(
                                                Intent(
                                                    context,
                                                    ScreenCaptureService::class.java
                                                )
                                            )
                                            viewModel.addServerLog(
                                                "主动停止了屏幕捕捉进程",
                                                com.example.ui.LogType.WARNING
                                            )
                                        } else {
                                            onRequestScreenShare()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isScreenCaptureRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
                                    ),
                                    contentPadding = PaddingValues(
                                        horizontal = 12.dp,
                                        vertical = 4.dp
                                    ),
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    Text(
                                        if (isScreenCaptureRunning) "停投" else "启投",
                                        fontSize = 12.sp
                                    )
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
                                        .background(
                                            if (isAccessibilityRunning) Color(
                                                0xFF4CAF50
                                            ) else Color.Red,
                                            CircleShape
                                        )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "全局仿真触控无障碍手势",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = if (isAccessibilityRunning) "无障碍勾住激活，接收动作时可模拟点击" else "未授权，无法代理模拟动作映射",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Button(
                                    onClick = {
                                        try {
                                            val intent =
                                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                                }
                                            context.startActivity(intent)
                                            Toast.makeText(
                                                context,
                                                "请在系统页面找到「屏幕共控」服务并启用权限",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        } catch (_: Exception) {
                                            Toast.makeText(
                                                context,
                                                "跳转失败，请到系统设置开启无障碍",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                                    contentPadding = PaddingValues(
                                        horizontal = 12.dp,
                                        vertical = 4.dp
                                    ),
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
                        containerColor = if (connectionState == ConnectionState.Connected) MaterialTheme.colorScheme.secondaryContainer.copy(
                            alpha = 0.25f
                        )
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
                                Icon(
                                    Icons.Default.Phone,
                                    contentDescription = "Client",
                                    tint = MaterialTheme.colorScheme.secondary
                                )
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
                        if (connectionState == ConnectionState.Connected && hasFrameReceived) {
                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(
                                    alpha = 0.5f
                                )
                            )
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
                            val aspectRatio =
                                if (mirroredWidth > 0) mirroredHeight.toFloat() / mirroredWidth.toFloat() else 16f / 9f
                            val currentWidthDp = clientScreenWidthDp * scaleMultiplier
                            val currentHeightDp = currentWidthDp * aspectRatio

                            Slider(
                                value = scaleMultiplier,
                                onValueChange = { viewModel.updateFloatingScaleMultiplier(it) },
                                valueRange = 0.2f..0.8f,
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
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(
                                    alpha = 0.5f
                                )
                            )
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
                                    if (isFloatingWindowRunning) {
                                        val serviceIntent =
                                            Intent(context, FloatingWindowService::class.java)
                                        context.stopService(serviceIntent)
                                    } else {
                                        if (!Settings.canDrawOverlays(context)) {
                                            Toast.makeText(
                                                context,
                                                "请先授予：显示在其他应用上层 (悬浮窗权限)",
                                                Toast.LENGTH_LONG
                                            ).show()
                                            val intent = Intent(
                                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                "package:${context.packageName}".toUri()
                                            )
                                            context.startActivity(intent)
                                        } else {
                                            val serviceIntent =
                                                Intent(context, FloatingWindowService::class.java)
                                            context.startService(serviceIntent)
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isFloatingWindowRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = if (isFloatingWindowRunning) Icons.Default.Close else Icons.Default.Share,
                                        contentDescription = "Background Floating Window Toggle",
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(if (isFloatingWindowRunning) "关闭后台悬浮窗" else "开启后台显示自适应悬浮窗")
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
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                            alpha = 0.30f
                        )
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 日志分类栏选择滑块 (Tab Selector)
                            Row(
                                modifier = Modifier
                                    .background(
                                        MaterialTheme.colorScheme.surface,
                                        RoundedCornerShape(10.dp)
                                    )
                                    .padding(2.dp)
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

                            // 一键清空当前选定面板缓存日志 (Clear logs)
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

                        // 日志视图容器控制栏 (Box holding the tab contents)
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (activeLogTab == 0) {
                                // 服务端日志显示面板 (Server Logs)
                                if (serverLogs.isEmpty()) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "无服务端交互反馈事件...",
                                            color = MaterialTheme.colorScheme.outline,
                                            fontSize = 12.sp
                                        )
                                    }
                                } else {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        items(serverLogs) { log ->
                                            Row {
                                                Text(
                                                    "[${log.timestamp}] ",
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.outline
                                                )
                                                val col = when (log.type) {
                                                    com.example.ui.LogType.SUCCESS -> Color(
                                                        0xFF4CAF50
                                                    )

                                                    com.example.ui.LogType.WARNING -> Color(
                                                        0xFFFF9800
                                                    )

                                                    com.example.ui.LogType.INFO -> MaterialTheme.colorScheme.onSurface
                                                }
                                                Text(
                                                    log.message,
                                                    fontSize = 11.sp,
                                                    color = col,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                // 客户端/控制端日志显示面板 (Client Logs)
                                if (controllerLogs.isEmpty()) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "无客户端操作日志...",
                                            color = MaterialTheme.colorScheme.outline,
                                            fontSize = 12.sp
                                        )
                                    }
                                } else {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        items(controllerLogs) { log ->
                                            Row {
                                                Text(
                                                    "[${log.timestamp}] ",
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.outline
                                                )
                                                val col = when (log.type) {
                                                    com.example.ui.LogType.SUCCESS -> Color(
                                                        0xFF4CAF50
                                                    )

                                                    com.example.ui.LogType.WARNING -> Color(
                                                        0xFFFF9800
                                                    )

                                                    com.example.ui.LogType.INFO -> MaterialTheme.colorScheme.onSurface
                                                }
                                                Text(
                                                    log.message,
                                                    fontSize = 11.sp,
                                                    color = col,
                                                    fontWeight = FontWeight.Medium
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

            // 桌面流画面遥控映射已经通过桌面级别 overlay 悬浮窗机制专门处理，不再在此宿主容器内提供冗余画布。
        }
    }
}

/**
 * 依据服务端工作状态自适应返回彩色卡片边框
 * @param active 服务端是否正在开启中
 * @return 组装好的 BorderStroke 样式组件
 */
@Composable
fun borderStrokeForServer(active: Boolean): BorderStroke {
    return if (active) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * 依据控制客户端对准状态自适应返回彩色卡片边框
 * @param conState 客户端套接字连接阶段状态 [ConnectionState]
 * @return 组装好的 BorderStroke 样式组件
 */
@Composable
fun borderStrokeForClient(conState: ConnectionState): BorderStroke {
    return if (conState == ConnectionState.Connected) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.secondary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }
}
