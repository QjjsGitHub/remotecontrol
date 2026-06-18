package com.example.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.RemoteAccessibilityService
import com.example.ScreenCaptureService
import com.example.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

enum class Role {
    NONE,
    CONTROLLER,
    RECEIVER
}

enum class LogType {
    INFO,
    SUCCESS,
    WARNING
}

data class LogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: String,
    val message: String,
    val type: LogType
)

class LanRemoteViewModel : ViewModel() {

    companion object {
        const val TAG = "LanRemoteViewModel"
        var instance: LanRemoteViewModel? = null
            private set
    }

    // Current Screen Role
    private val _currentRole = MutableStateFlow(Role.NONE)
    val currentRole: StateFlow<Role> = _currentRole.asStateFlow()

    // Server State
    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning: StateFlow<Boolean> = _isServerRunning.asStateFlow()

    private val _serverIp = MutableStateFlow("127.0.0.1")
    val serverIp: StateFlow<String> = _serverIp.asStateFlow()

    private val _connectedClients = MutableStateFlow<List<String>>(emptyList())
    val connectedClients: StateFlow<List<String>> = _connectedClients.asStateFlow()

    private val _serverLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    val serverLogs: StateFlow<List<LogEntry>> = _serverLogs.asStateFlow()

    private val _serverWidth = MutableStateFlow(1080)
    private val _serverHeight = MutableStateFlow(2400)

    // Client (Controller) State
    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _discoveredServers = MutableStateFlow<Set<String>>(emptySet())
    val discoveredServers: StateFlow<Set<String>> = _discoveredServers.asStateFlow()

    private val _manualIpField = MutableStateFlow("")
    val manualIpField: StateFlow<String> = _manualIpField.asStateFlow()

    private val _controllerLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    val controllerLogs: StateFlow<List<LogEntry>> = _controllerLogs.asStateFlow()

    // Encoded H264 video frame chunks (Pair of raw packet bytes and MediaCodec flags)
    private val _encodedFrame = MutableStateFlow<Pair<ByteArray, Int>?>(null)
    val encodedFrame: StateFlow<Pair<ByteArray, Int>?> = _encodedFrame.asStateFlow()

    private val _mirroredWidth = MutableStateFlow(1080)
    val mirroredWidth: StateFlow<Int> = _mirroredWidth.asStateFlow()

    private val _mirroredHeight = MutableStateFlow(2400)
    val mirroredHeight: StateFlow<Int> = _mirroredHeight.asStateFlow()

    // 悬浮窗的缩放比例状态流，默认设为 1.0f (基准为客户端屏幕宽度的 1/3)
    // 使得 MainActivity 的 Slider 控制可直接与 FloatingWindowService 悬浮窗口保持实时、双向的同步适配
    private val _floatingScaleMultiplier = MutableStateFlow(1.0f)
    val floatingScaleMultiplier: StateFlow<Float> = _floatingScaleMultiplier.asStateFlow()

    /**
     * 更新全局悬浮窗的缩放比例
     * @param value 新的缩放比例值
     */
    fun updateFloatingScaleMultiplier(value: Float) {
        _floatingScaleMultiplier.value = value
    }

    // Network component references
    private var socketServer: SocketServer? = null
    private var udpBroadcaster: UdpBroadcaster? = null
    private var udpListener: UdpListener? = null
    private var socketClient: SocketClient? = null

    init {
        instance = this
        startDiscovery()
    }

    fun selectRole(role: Role, context: Context? = null) {
        _currentRole.value = role
        
        // Clean up previous instances on transition
        stopServer(context)
        stopDiscovery()
        disconnectFromServer()

        when (role) {
            Role.CONTROLLER -> {
                startDiscovery()
            }
            Role.RECEIVER -> {
                context?.let { startServer(it) }
            }
            Role.NONE -> {
                _encodedFrame.value = null
            }
        }
    }

    private fun getCurrentTimestamp(): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }

    fun addServerLog(message: String, type: LogType = LogType.INFO) {
        val entry = LogEntry(timestamp = getCurrentTimestamp(), message = message, type = type)
        _serverLogs.value = listOf(entry) + _serverLogs.value
    }

    fun addControllerLog(message: String, type: LogType = LogType.INFO) {
        val entry = LogEntry(timestamp = getCurrentTimestamp(), message = message, type = type)
        _controllerLogs.value = listOf(entry) + _controllerLogs.value
    }

    fun clearServerLogs() {
        _serverLogs.value = emptyList()
    }

    fun clearControllerLogs() {
        _controllerLogs.value = emptyList()
    }

    // ----------------------------------------------------
    // SERVER CONTROL LOGIC
    // ----------------------------------------------------
    fun startServer(context: Context) {
        if (_isServerRunning.value) return

        viewModelScope.launch {
            val ip = withContext(Dispatchers.IO) { getLocalIpAddress() }
            _serverIp.value = ip

            // Start TCP Server socket
            socketServer = SocketServer(
                port = 9292,
                onClientConnected = { clientIp ->
                    _connectedClients.value = (_connectedClients.value + clientIp).distinct()
                    addServerLog("客户端已连接: $clientIp", LogType.SUCCESS)
                    
                    // Immediately synchronize size specifications
                    socketServer?.broadcastMessage("SIZE:${_serverWidth.value},${_serverHeight.value}")
                },
                onClientDisconnected = { clientIp ->
                    _connectedClients.value = _connectedClients.value - clientIp
                    addServerLog("客户端已断开: $clientIp", LogType.WARNING)
                },
                onCommandReceived = { clientIp, command ->
                    handleReceivedCommand(context, clientIp, command)
                }
            )
            socketServer?.start()

            // Enable discoverability over LAN using UDP broadcasting
            udpBroadcaster = UdpBroadcaster(serverIp = ip, port = 9293)
            udpBroadcaster?.start()

            _isServerRunning.value = true
            addServerLog("共控服务端开启. 本机 IP: $ip", LogType.SUCCESS)
            addServerLog("等待通过设置启用录屏及绑定辅助服务...", LogType.INFO)
        }
    }

    fun stopServer(context: Context?) {
        if (!_isServerRunning.value) return

        socketServer?.stop()
        socketServer = null

        udpBroadcaster?.stop()
        udpBroadcaster = null

        // Stop core recording foreground service
        context?.let { ctx ->
            try {
                ctx.stopService(Intent(ctx, ScreenCaptureService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop recording service: ${e.message}")
            }
        }

        _connectedClients.value = emptyList()
        _isServerRunning.value = false
        addServerLog("服务端已安全停止", LogType.WARNING)
    }

    /**
     * Set up real dimensions when screen sizes are fetched
     */
    fun onScreenSizeDetermined(width: Int, height: Int) {
        _serverWidth.value = width
        _serverHeight.value = height
        addServerLog("本机录屏分辨率初始化完成: ${width}x${height}", LogType.INFO)
        socketServer?.broadcastMessage("SIZE:$width,$height")
    }

    /**
     * Invoked continuously by ScreenCaptureService to broadcast H.264 video chunks
     */
    fun onEncodedFrameCaptured(base64Data: String, flags: Int) {
        socketServer?.broadcastMessage("H264:$flags:$base64Data")
    }

    /**
     * Executes back-injected events simulating the client's input actions on the server
     */
    private fun handleReceivedCommand(context: Context, clientIp: String, command: String) {
        when {
            command.startsWith("TAP:") -> {
                try {
                    val coords = command.substringAfter("TAP:").split(",")
                    if (coords.size == 2) {
                        val rx = coords[0].toFloatOrNull() ?: return
                        val ry = coords[1].toFloatOrNull() ?: return
                        
                        // Scale percentage values back to real resolution coordinates
                        val originalX = rx * _serverWidth.value
                        val originalY = ry * _serverHeight.value

                        val injected = RemoteAccessibilityService.performTap(originalX, originalY)
                        if (injected) {
                            addServerLog("成功模拟点击坐标: (${originalX.toInt()}, ${originalY.toInt()})", LogType.SUCCESS)
                        } else {
                            addServerLog("模拟点击失败: 辅助功能运行状态 [${RemoteAccessibilityService.isServiceRunning()}]", LogType.WARNING)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling TAP command: ${e.message}")
                }
            }
            command.startsWith("SWIPE:") -> {
                try {
                    val paths = command.substringAfter("SWIPE:").split(";")
                    if (paths.size == 2) {
                        val start = paths[0].split(",")
                        val end = paths[1].split(",")
                        if (start.size == 2 && end.size == 2) {
                            val rsx = start[0].toFloatOrNull() ?: return
                            val rsy = start[1].toFloatOrNull() ?: return
                            val rex = end[0].toFloatOrNull() ?: return
                            val rey = end[1].toFloatOrNull() ?: return

                            val sx = rsx * _serverWidth.value
                            val sy = rsy * _serverHeight.value
                            val ex = rex * _serverWidth.value
                            val ey = rey * _serverHeight.value

                            val injected = RemoteAccessibilityService.performSwipe(sx, sy, ex, ey)
                            if (injected) {
                                addServerLog("成功模拟滑动: (${sx.toInt()}, ${sy.toInt()}) -> (${ex.toInt()}, ${ey.toInt()})", LogType.SUCCESS)
                            } else {
                                addServerLog("模拟滑动失败: 轴值映射验证失败", LogType.WARNING)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling SWIPE command: ${e.message}")
                }
            }
        }
    }


    // ----------------------------------------------------
    // CLIENT (CONTROLLER) LOGIC
    // ----------------------------------------------------
    fun startDiscovery() {
        _discoveredServers.value = emptySet()
        udpListener?.stop()
        udpListener = UdpListener(port = 9293) { ip ->
            val set = _discoveredServers.value.toMutableSet()
            if (set.add(ip)) {
                _discoveredServers.value = set
                addControllerLog("发现局域网可用共控端设备: $ip", LogType.SUCCESS)
            }
        }
        udpListener?.start()
        addControllerLog("已开启局域网探针寻找服务端设备...", LogType.INFO)
    }

    fun stopDiscovery() {
        udpListener?.stop()
        udpListener = null
    }

    fun setManualIp(ip: String) {
        _manualIpField.value = ip
    }

    fun selectServer(ip: String) {
        _manualIpField.value = ip
    }

    /**
     * Decodes and displays video stream frames received from the server
     */
    fun handleReceivedMessage(message: String) {
        when {
            message.startsWith("H264:") -> {
                try {
                    val remainder = message.substringAfter("H264:")
                    val colonIndex = remainder.indexOf(':')
                    if (colonIndex != -1) {
                        val flagsStr = remainder.substring(0, colonIndex)
                        val base64Bytes = remainder.substring(colonIndex + 1)
                        val flags = flagsStr.toIntOrNull() ?: 0
                        val bytes = Base64.decode(base64Bytes, Base64.DEFAULT)
                        _encodedFrame.value = Pair(bytes, flags)
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Video compression slice decode trigger error: ${e.message}")
                }
            }
            message.startsWith("SIZE:") -> {
                try {
                    val sizes = message.substringAfter("SIZE:").split(",")
                    if (sizes.size == 2) {
                        val w = sizes[0].toIntOrNull() ?: 1080
                        val h = sizes[1].toIntOrNull() ?: 2400
                        _mirroredWidth.value = w
                        _mirroredHeight.value = h
                        addControllerLog("更新远端显示分辨率匹配: ${w}x${h}", LogType.INFO)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse screen dimensions: ${e.message}")
                }
            }
        }
    }

    fun connectToSelectedServer(context: Context) {
        val ip = _manualIpField.value.trim()
        if (ip.isEmpty()) {
            addControllerLog("连接失败: Server IP 不能为空码", LogType.WARNING)
            return
        }

        disconnectFromServer()

        addControllerLog("正在尝试打通通道连接 $ip ...", LogType.INFO)
        socketClient = SocketClient(
            serverIp = ip,
            onStateChanged = { state ->
                _connectionState.value = state
                when (state) {
                    ConnectionState.Connected -> {
                        addControllerLog("已打通连接，远程画面加载中...", LogType.SUCCESS)
                    }
                    ConnectionState.Disconnected -> {
                        addControllerLog("服务端已被断开", LogType.WARNING)
                        _encodedFrame.value = null
                    }
                    else -> {}
                }
            },
            onMessageReceived = { message ->
                handleReceivedMessage(message)
            }
        )
        socketClient?.connect()
    }

    fun sendClientAction(actionCommand: String) {
        val client = socketClient
        if (client != null && _connectionState.value == ConnectionState.Connected) {
            client.sendCommand(actionCommand)
        }
    }

    fun disconnectFromServer() {
        socketClient?.disconnect()
        socketClient = null
        _connectionState.value = ConnectionState.Disconnected
        _encodedFrame.value = null
    }

    override fun onCleared() {
        stopServer(null)
        stopDiscovery()
        disconnectFromServer()
        if (instance == this) {
            instance = null
        }
        super.onCleared()
    }
}
