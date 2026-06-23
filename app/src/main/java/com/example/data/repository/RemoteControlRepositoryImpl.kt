package com.example.data.repository

import com.example.data.model.ClientInfo
import com.example.data.model.ConnectionState
import com.example.data.model.LogEntry
import com.example.data.model.LogType
import com.example.data.model.RemoteCommand
import com.example.data.model.ScreenSize
import com.example.data.model.ServerInfo
import com.example.data.model.ServerState
import com.example.data.model.VideoFrame
import com.example.network.NetworkConstants
import com.example.network.SocketClient
import com.example.network.SocketServer
import com.example.network.UdpBroadcaster
import com.example.network.UdpListener
import com.example.network.getLocalIpAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 远程控制Repository实现
 */
class RemoteControlRepositoryImpl(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : RemoteControlRepository {

    companion object {
        private const val TAG = "RemoteControlRepository"
        private const val MAX_LOG_ENTRIES = 255
        private const val DEFAULT_WIDTH = 1080
        private const val DEFAULT_HEIGHT = 2400
    }

    // ========== 服务器端状态 ==========
    private val _serverState = MutableStateFlow<ServerState>(ServerState.Stopped)
    override val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    private val _serverIp = MutableStateFlow("127.0.0.1")
    override val serverIp: StateFlow<String> = _serverIp.asStateFlow()

    private val _connectedClients = MutableStateFlow<List<ClientInfo>>(emptyList())
    override val connectedClients: StateFlow<List<ClientInfo>> = _connectedClients.asStateFlow()

    // 服务端屏幕尺寸
    private val _serverWidth = MutableStateFlow(DEFAULT_WIDTH)
    private val _serverHeight = MutableStateFlow(DEFAULT_HEIGHT)

    // ========== 客户端状态 ==========
    private val _clientConnectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val clientConnectionState: StateFlow<ConnectionState> = _clientConnectionState.asStateFlow()

    private val _discoveredServers = MutableStateFlow<Set<ServerInfo>>(emptySet())
    override val discoveredServers: StateFlow<Set<ServerInfo>> = _discoveredServers.asStateFlow()

    private val _videoFrames = MutableSharedFlow<VideoFrame>(extraBufferCapacity = 32)
    override val videoFrames: Flow<VideoFrame> = _videoFrames.asSharedFlow()

    private val _mirroredWidth = MutableStateFlow(DEFAULT_WIDTH)
    override val mirroredWidth: StateFlow<Int> = _mirroredWidth.asStateFlow()

    private val _mirroredHeight = MutableStateFlow(DEFAULT_HEIGHT)
    override val mirroredHeight: StateFlow<Int> = _mirroredHeight.asStateFlow()

    private val _hasFrameReceived = MutableStateFlow(false)
    override val hasFrameReceived: StateFlow<Boolean> = _hasFrameReceived.asStateFlow()

    private val _floatingScaleMultiplier = MutableStateFlow(0.3f)
    override val floatingScaleMultiplier: StateFlow<Float> = _floatingScaleMultiplier.asStateFlow()

    private val _manualIpField = MutableStateFlow("")
    override val manualIpField: StateFlow<String> = _manualIpField.asStateFlow()

    // ========== 日志状态 ==========
    private val _serverLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    override val serverLogs: StateFlow<List<LogEntry>> = _serverLogs.asStateFlow()

    private val _clientLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    override val clientLogs: StateFlow<List<LogEntry>> = _clientLogs.asStateFlow()

    // ========== 网络组件 ==========
    private var socketServer: SocketServer? = null
    private var socketClient: SocketClient? = null
    private var udpBroadcaster: UdpBroadcaster? = null
    private var udpListener: UdpListener? = null

    // 回调接口，供 Service 设置
    private var commandHandler: ((RemoteCommand) -> Unit)? = null

    fun setCommandHandler(handler: (RemoteCommand) -> Unit) {
        this.commandHandler = handler
    }

    // ========== 服务器端操作实现 ==========

    override suspend fun startServer(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (_serverState.value is ServerState.Running) {
                return@withContext Result.success(Unit)
            }

            val ip = getLocalIpAddress()
            _serverIp.value = ip
            addServerLog("正在启动服务器...", LogType.INFO)

            socketServer = SocketServer(
                port = NetworkConstants.TCP_PORT,
                onClientConnected = { clientIp ->
                    _connectedClients.value = (_connectedClients.value + ClientInfo(clientIp)).distinctBy { it.ip }
                    addServerLog("客户端已连接: $clientIp", LogType.SUCCESS)
                    
                    // 广播当前屏幕尺寸给新连接的客户端
                    scope.launch {
                        broadcastScreenSize(ScreenSize(_serverWidth.value, _serverHeight.value, if (_serverWidth.value > _serverHeight.value) 1 else 0))
                    }
                },
                onClientDisconnected = { clientIp ->
                    _connectedClients.value = _connectedClients.value.filter { it.ip != clientIp }
                    addServerLog("客户端已断开: $clientIp", LogType.WARNING)
                },
                onCommandReceived = { clientIp, commandStr ->
                    val command = RemoteCommand.fromProtocolString(commandStr)
                    if (command != null) {
                        scope.launch {
                            handleClientCommand(command)
                        }
                    } else {
                        addServerLog("收到来自 [$clientIp] 的未知指令: $commandStr", LogType.WARNING)
                    }
                },
                onError = { error ->
                    addServerLog(error, LogType.ERROR)
                }
            )
            socketServer?.start()

            udpBroadcaster = UdpBroadcaster(
                serverIp = ip,
                port = NetworkConstants.UDP_PORT,
                onError = { error -> addServerLog(error, LogType.ERROR) }
            )
            udpBroadcaster?.start()

            _serverState.value = ServerState.Running(ip, NetworkConstants.TCP_PORT)
            addServerLog("服务器已启动: $ip:${NetworkConstants.TCP_PORT}", LogType.SUCCESS)
            Result.success(Unit)
        } catch (e: Exception) {
            addServerLog("服务器启动失败: ${e.message}", LogType.ERROR)
            Result.failure(e)
        }
    }

    override suspend fun stopServer(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            socketServer?.stop()
            socketServer = null

            udpBroadcaster?.stop()
            udpBroadcaster = null

            _connectedClients.value = emptyList()
            _serverState.value = ServerState.Stopped
            addServerLog("服务器已停止", LogType.INFO)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun broadcastVideoFrame(frame: VideoFrame) {
        broadcastVideoFrame(frame.data, frame.flags, frame.pts)
    }

    override suspend fun broadcastVideoFrame(data: ByteArray, flags: Int, pts: Long) {
        val buffer = java.nio.ByteBuffer.allocate(1 + 4 + 8 + data.size)
        buffer.put(NetworkConstants.TYPE_VIDEO.toByte())
        buffer.putInt(flags)
        buffer.putLong(pts)
        buffer.put(data)
        socketServer?.broadcastData(buffer.array())
    }

    override suspend fun broadcastScreenSize(size: ScreenSize) {
        val message = "SIZE:${size.width},${size.height},${size.rotation}"
        val sizeMsg = message.toByteArray(Charsets.UTF_8)
        val buffer = java.nio.ByteBuffer.allocate(1 + sizeMsg.size)
        buffer.put(NetworkConstants.TYPE_SIZE.toByte())
        buffer.put(sizeMsg)
        socketServer?.broadcastData(buffer.array())
    }

    override suspend fun handleClientCommand(command: RemoteCommand) {
        withContext(Dispatchers.Main) {
            commandHandler?.invoke(command)
        }
    }

    // ========== 客户端操作实现 ==========

    override suspend fun startDiscovery() {
        _discoveredServers.value = emptySet()
        udpListener?.stop()
        udpListener = UdpListener(
            port = NetworkConstants.UDP_PORT,
            onServersUpdated = { serverSet ->
                val oldSet = _discoveredServers.value.map { it.ip }.toSet()
                _discoveredServers.value = serverSet.map { ServerInfo(it) }.toSet()

                (serverSet - oldSet).forEach { ip ->
                    addClientLog("发现局域网可用设备: $ip", LogType.SUCCESS)
                }
            },
            onError = { error -> addClientLog(error, LogType.ERROR) }
        )
        udpListener?.start()
        addClientLog("已开启局域网探针...", LogType.INFO)
    }

    override suspend fun stopDiscovery() {
        udpListener?.stop()
        udpListener = null
    }

    override suspend fun connectToServer(serverInfo: ServerInfo): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            disconnect()
            addClientLog("正在连接到服务端: ${serverInfo.ip}...", LogType.INFO)
            
            socketClient = SocketClient(
                serverIp = serverInfo.ip,
                onStateChanged = { state ->
                    _clientConnectionState.value = state
                    
                    if (state == ConnectionState.Connected) {
                        addClientLog("已建立连接", LogType.SUCCESS)
                    } else if (state == ConnectionState.Disconnected) {
                        addClientLog("连接已断开", LogType.WARNING)
                        _hasFrameReceived.value = false
                    }
                },
                onMessageReceived = { handleClientData(it) },
                onError = { error -> addClientLog(error, LogType.ERROR) }
            )
            socketClient?.connect()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun disconnect(): Result<Unit> = withContext(Dispatchers.IO) {
        socketClient?.disconnect()
        socketClient = null
        _clientConnectionState.value = ConnectionState.Disconnected
        _hasFrameReceived.value = false
        Result.success(Unit)
    }

    override suspend fun sendCommand(command: String): Result<Unit> = withContext(Dispatchers.IO) {
        socketClient?.sendCommand(command)
        Result.success(Unit)
    }

    override fun setManualIp(ip: String) {
        _manualIpField.value = ip
    }

    override fun selectServer(serverInfo: ServerInfo) {
        _manualIpField.value = serverInfo.ip
    }

    override fun updateFloatingScaleMultiplier(value: Float) {
        _floatingScaleMultiplier.value = value
    }

    // ========== 日志操作实现 ==========

    override fun addServerLog(message: String, type: LogType) {
        val entry = LogEntry(timestamp = getCurrentTimestamp(), message = message, type = type)
        _serverLogs.value = (listOf(entry) + _serverLogs.value).take(MAX_LOG_ENTRIES)
    }

    override fun addClientLog(message: String, type: LogType) {
        val entry = LogEntry(timestamp = getCurrentTimestamp(), message = message, type = type)
        _clientLogs.value = (listOf(entry) + _clientLogs.value).take(MAX_LOG_ENTRIES)
    }

    override fun clearServerLogs() {
        _serverLogs.value = emptyList()
    }

    override fun clearClientLogs() {
        _clientLogs.value = emptyList()
    }

    // ========== 配置操作实现 ==========

    override fun setServerScreenSize(width: Int, height: Int) {
        _serverWidth.value = width
        _serverHeight.value = height
    }

    override fun getServerScreenSize(): Pair<Int, Int> {
        return Pair(_serverWidth.value, _serverHeight.value)
    }

    // ========== 内部辅助方法 ==========

    private fun handleClientData(payload: ByteArray) {
        try {
            val buffer = java.nio.ByteBuffer.wrap(payload)
            val type = buffer.get().toInt()

            when (type) {
                NetworkConstants.TYPE_VIDEO -> {
                    val flags = buffer.int
                    val pts = buffer.long
                    val data = ByteArray(buffer.remaining())
                    buffer.get(data)
                    
                    scope.launch {
                        _videoFrames.emit(VideoFrame(data, flags, pts))
                        _hasFrameReceived.value = true
                    }
                }
                NetworkConstants.TYPE_SIZE -> {
                    val message = String(ByteArray(buffer.remaining()).apply { buffer.get(this) }, Charsets.UTF_8)
                    if (message.startsWith("SIZE:")) {
                        val sizes = message.substringAfter("SIZE:").split(",")
                        if (sizes.size >= 3) {
                            _mirroredWidth.value = sizes[0].toIntOrNull() ?: DEFAULT_WIDTH
                            _mirroredHeight.value = sizes[1].toIntOrNull() ?: DEFAULT_HEIGHT
                            val rot = if (sizes[2].toIntOrNull() == 1) "横屏" else "竖屏"
                            addClientLog("同步远端分辨率: ${_mirroredWidth.value}x${_mirroredHeight.value} ($rot)", LogType.INFO)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            addClientLog("数据解析异常: ${e.message}", LogType.ERROR)
        }
    }

    private fun getCurrentTimestamp(): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }
}