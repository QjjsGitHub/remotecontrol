package com.example.ui

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.RemoteAccessibilityService
import com.example.ScreenCaptureService
import com.example.network.ConnectionState
import com.example.network.NetworkConstants
import com.example.network.SocketClient
import com.example.network.SocketServer
import com.example.network.UdpBroadcaster
import com.example.network.UdpListener
import com.example.network.getLocalIpAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class LogType {
    INFO,
    SUCCESS,
    WARNING
}

/**
 * ViewModel 相关常量
 */
private object ViewModelConstants {
    /** 最大日志条目数 */
    const val MAX_LOG_ENTRIES = 255

    /** 默认视频流宽度 */
    const val DEFAULT_WIDTH = 1080

    /** 默认视频流高度 */
    const val DEFAULT_HEIGHT = 2400

    /** 录屏缩放比例 */
    const val CAPTURE_SCALE = 0.8f
}

/**
 * IPv4 地址验证
 */
private object IpValidator {
    private const val IPV4_PATTERN =
        "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"

    /**
     * 验证字符串是否为有效的 IPv4 地址
     * @param ip 待验证的 IP 地址字符串
     * @return 如果有效返回 true，否则返回 false
     */
    fun isValidIPv4(ip: String): Boolean {
        return IPV4_PATTERN.toRegex().matches(ip.trim())
    }
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

    // 被控端(服务端)运行状态
    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning: StateFlow<Boolean> = _isServerRunning.asStateFlow()

    // 本机在局域网中的物理 IPv4 地址
    private val _serverIp = MutableStateFlow("127.0.0.1")
    val serverIp: StateFlow<String> = _serverIp.asStateFlow()

    // 当前已接入服务端的控制端客户端 IP 列表
    private val _connectedClients = MutableStateFlow<List<String>>(emptyList())

    // 被控端本地系统与网络交互日志
    private val _serverLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    val serverLogs: StateFlow<List<LogEntry>> = _serverLogs.asStateFlow()

    // 服务端(被控端)的屏幕物理真实宽度和高度尺寸
    private val _serverWidth = MutableStateFlow(ViewModelConstants.DEFAULT_WIDTH)
    private val _serverHeight = MutableStateFlow(ViewModelConstants.DEFAULT_HEIGHT)

    // 控制端(客户端)状态：连接、正在连接、未连接
    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // 局域网内通过 UDP 探针寻找到的多路服务端地址集
    private val _discoveredServers = MutableStateFlow<Set<String>>(emptySet())
    val discoveredServers: StateFlow<Set<String>> = _discoveredServers.asStateFlow()

    // 控制端手动输入的远端 IP 地址字段
    private val _manualIpField = MutableStateFlow("")
    val manualIpField: StateFlow<String> = _manualIpField.asStateFlow()

    // 控制端交互行为与流状态控制日志
    private val _controllerLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    val controllerLogs: StateFlow<List<LogEntry>> = _controllerLogs.asStateFlow()

    // 标志当前控制端是否接收到了至少一个有效的 H264 视频流编码切片帧
    private val _hasFrameReceived = MutableStateFlow(false)
    val hasFrameReceived: StateFlow<Boolean> = _hasFrameReceived.asStateFlow()

    // 未合并累积的视频流数据切片热流，提供给悬浮窗底层的 MediaCodec 硬件解码器直接读取
    private val _encodedFrameFlow =
        MutableSharedFlow<Triple<ByteArray, Int, Long>>(extraBufferCapacity = 32)
    val encodedFrameFlow: SharedFlow<Triple<ByteArray, Int, Long>> =
        _encodedFrameFlow.asSharedFlow()


    // 同步镜像的物理参考大屏宽度
    private val _mirroredWidth = MutableStateFlow(ViewModelConstants.DEFAULT_WIDTH)
    val mirroredWidth: StateFlow<Int> = _mirroredWidth.asStateFlow()

    // 同步镜像的物理参考大屏高度
    private val _mirroredHeight = MutableStateFlow(ViewModelConstants.DEFAULT_HEIGHT)
    val mirroredHeight: StateFlow<Int> = _mirroredHeight.asStateFlow()

    // 悬浮窗的缩放比例状态流，默认设为 1.0f (基准为客户端屏幕宽度的 1/3)
    // 使得 MainActivity 的 Slider 控制可直接与 FloatingWindowService 悬浮窗口保持实时、双向地同步适配
    private val _floatingScaleMultiplier = MutableStateFlow(0.3f)
    val floatingScaleMultiplier: StateFlow<Float> = _floatingScaleMultiplier.asStateFlow()

    /**
     * 更新全局悬浮窗的缩放比例
     * @param value 新地缩放比例值
     */
    fun updateFloatingScaleMultiplier(value: Float) {
        _floatingScaleMultiplier.value = value
    }

    // 各大局域网、TCP/UDP套接字核心网络组件和缓存定义
    private var socketServer: SocketServer? = null
    private var udpBroadcaster: UdpBroadcaster? = null
    private var udpListener: UdpListener? = null
    private var socketClient: SocketClient? = null

    init {
        instance = this
        // 默认初始化即开启局域网探针，以便及时嗅探周边的主机节点
        startDiscovery()
    }

    /**
     * 快捷获取格式化好的时分秒当前系统时间戳标记
     * @return 格式化后的时间字符序列 (HH:mm:ss)
     */
    private fun getCurrentTimestamp(): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }

    /**
     * 向服务端日志队列列表中头插新纪录
     * @param message 日志消息文本内容
     * @param type 日志重要等级类型 [LogType]（正常消息/成功通知/警告）
     */
    fun addServerLog(message: String, type: LogType = LogType.INFO) {
        val entry = LogEntry(timestamp = getCurrentTimestamp(), message = message, type = type)
        _serverLogs.value =
            listOf(entry) + _serverLogs.value.take(ViewModelConstants.MAX_LOG_ENTRIES)
    }

    /**
     * 向控制端日志队列列表中头插新纪录
     * @param message 日志消息文本内容
     * @param type 日志重要等级类型 [LogType]（正常消息/成功通知/警告）
     */
    fun addControllerLog(message: String, type: LogType = LogType.INFO) {
        val entry = LogEntry(timestamp = getCurrentTimestamp(), message = message, type = type)
        _controllerLogs.value =
            listOf(entry) + _controllerLogs.value.take(ViewModelConstants.MAX_LOG_ENTRIES)
    }

    /**
     * 清空当前服务端保存的全量交互事件日志
     */
    fun clearServerLogs() {
        _serverLogs.value = emptyList()
    }

    /**
     * 清空当前控制端保存的全量交互事件日志
     */
    fun clearControllerLogs() {
        _controllerLogs.value = emptyList()
    }

    // ----------------------------------------------------
    // 被控端服务端控制管理逻辑 (SERVER CONTROL LOGIC)
    // ----------------------------------------------------

    /**
     * 开启局域网共控服务端，初始化并启动 TCP 服务和局域网嗅探探测广播
     * @param context 加载上下文，启动多媒体录屏及辅助服务等需要
     */
    fun startServer() {
        if (_isServerRunning.value) return

        viewModelScope.launch {
            val ip = withContext(Dispatchers.IO) { getLocalIpAddress() }
            _serverIp.value = ip

            // 开启 TCP 套接字监听
            socketServer = SocketServer(
                port = NetworkConstants.TCP_PORT,
                onClientConnected = { clientIp ->
                    _connectedClients.value = (_connectedClients.value + clientIp).distinct()
                    addServerLog("客户端已连接: $clientIp", LogType.SUCCESS)
                    updateScreenSize()
                },
                onClientDisconnected = { clientIp ->
                    _connectedClients.value = _connectedClients.value - clientIp
                    addServerLog("客户端已断开: $clientIp", LogType.WARNING)
                },
                onCommandReceived = { clientIp, command ->
                    handleReceivedCommand(clientIp, command)
                },
                onError = { error ->
                    addServerLog(error, LogType.WARNING)
                }
            )
            socketServer?.start()

            // 建立局域网 UDP 定时广播器
            udpBroadcaster = UdpBroadcaster(
                serverIp = ip,
                port = NetworkConstants.UDP_PORT,
                onError = { error -> addServerLog(error, LogType.WARNING) }
            )
            udpBroadcaster?.start()

            _isServerRunning.value = true
            addServerLog("共控服务端开启. 本机 IP: $ip", LogType.SUCCESS)
            addServerLog("等待通过设置启用录屏及绑定辅助服务...", LogType.INFO)
        }
    }

    /**
     * 优雅而安全地闭合与停止服务端套接字传输、UDP 广播和投屏前台服务
     * @param context 系统上下文环境，用于停止 ScreenCaptureService 服务
     */
    fun stopServer(context: Context?) {
        if (!_isServerRunning.value) return

        socketServer?.stop()
        socketServer = null

        udpBroadcaster?.stop()
        udpBroadcaster = null

        // 重置并销毁前台屏幕像素录制采集服务
        context?.let { ctx ->
            try {
                ctx.stopService(Intent(ctx, ScreenCaptureService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "注销视频采集服务异常: ${e.message}")
            }
        }

        _connectedClients.value = emptyList()
        _isServerRunning.value = false
        addServerLog("服务端已安全停止", LogType.WARNING)
    }

    fun updateScreenSize() {
        onScreenSizeDetermined(_serverWidth.value, _serverHeight.value)
    }

    /**
     * 当收到系统的真实屏幕投射渲染回调，确定当前设备物理采集的长宽像素尺寸
     * @param width 系统屏幕的物理总宽度(像素)
     * @param height 系统屏幕的物理总高度(像素)
     */
    fun onScreenSizeDetermined(width: Int, height: Int) {
        _serverWidth.value = width
        _serverHeight.value = height

        // 自动判定旋转状态：0 竖屏，1 横屏
        val rotation = if (width > height) 1 else 0

        val (captureWidth, captureHeight) = calculateCaptureSize(width, height)

        addServerLog(
            "本机录屏分辨率初始化完成: ${width}x${height} (视频流分辨率: ${captureWidth}x${captureHeight}), 旋转状态: $rotation",
            LogType.INFO
        )
        // 构造二进制包：1 byte(类型:3代表SIZE) + N bytes(字符串数据)
        val sizeMsg =
            "SIZE:$captureWidth,$captureHeight,$rotation".toByteArray(Charsets.UTF_8)
        val buffer = java.nio.ByteBuffer.allocate(1 + sizeMsg.size)
        buffer.put(NetworkConstants.TYPE_SIZE.toByte())
        buffer.put(sizeMsg)

        socketServer?.broadcastData(buffer.array())
    }


    /**
     * 高效处理视频帧捕获（直接处理 ByteBuffer 减少内存分配）
     */
    fun onEncodedFrameCaptured(
        codecBuffer: java.nio.ByteBuffer,
        bufferInfo: android.media.MediaCodec.BufferInfo
    ) {
        val size = bufferInfo.size
        val flags = bufferInfo.flags
        val pts = bufferInfo.presentationTimeUs

        // 核心优化：只分配一次最终的网络包数组 (1 byte 类型 + 4 bytes Flags + 8 bytes PTS + 实际数据)
        val packet = ByteArray(1 + 4 + 8 + size)
        val wrap = java.nio.ByteBuffer.wrap(packet)
        wrap.put(NetworkConstants.TYPE_VIDEO.toByte())
        wrap.putInt(flags)
        wrap.putLong(pts)

        // 直接从硬件 Buffer 拷贝到最终的网络数组，跳过中间层
        val oldPos = codecBuffer.position()
        val oldLimit = codecBuffer.limit()
        try {
            codecBuffer.position(bufferInfo.offset)
            codecBuffer.limit(bufferInfo.offset + size)
            codecBuffer.get(packet, 13, size)
        } finally {
            codecBuffer.position(oldPos)
            codecBuffer.limit(oldLimit)
        }

        socketServer?.broadcastData(packet)
    }

    // 保留原方法签名供补发配置帧等 ByteArray 场景使用
    fun onEncodedFrameCaptured(data: ByteArray, flags: Int, presentationTimeUs: Long) {
        // 构造二进制包：1 byte(类型:2代表视频) + 4 bytes(Flags) + 8 bytes(PTS) + N bytes(视频数据)
        val buffer = java.nio.ByteBuffer.allocate(1 + 4 + 8 + data.size)
        buffer.put(NetworkConstants.TYPE_VIDEO.toByte())
        buffer.putInt(flags)
        buffer.putLong(presentationTimeUs)
        buffer.put(data)

        val packet = buffer.array()
        socketServer?.broadcastData(packet)
    }

    /**
     * 接收并解析控制端传输上来的模拟反控指令
     * @param context 上下文
     * @param clientIp 来源端的 IP 地址
     * @param command 命令参数协议串
     */
    private fun handleReceivedCommand(clientIp: String, command: String) {
        try {
            when {
                command.startsWith("TAP:") -> {
                    val coords = command.substringAfter("TAP:").split(",")
                    if (coords.size == 2) {
                        val rx = coords[0].toFloatOrNull() ?: return
                        val ry = coords[1].toFloatOrNull() ?: return

                        val originalX = rx * _serverWidth.value
                        val originalY = ry * _serverHeight.value

                        if (RemoteAccessibilityService.performTap(originalX, originalY)) {
                            addServerLog(
                                "[$clientIp] 模拟点击: (${originalX.toInt()}, ${originalY.toInt()})",
                                LogType.SUCCESS
                            )
                        } else {
                            addServerLog("[$clientIp] 点击失败: 无障碍服务未运行", LogType.WARNING)
                        }
                    }
                }

                command.startsWith("DOUBLE_TAP:") -> {
                    val coords = command.substringAfter("DOUBLE_TAP:").split(",")
                    if (coords.size == 2) {
                        val rx = coords[0].toFloatOrNull() ?: return
                        val ry = coords[1].toFloatOrNull() ?: return
                        val x = rx * _serverWidth.value
                        val y = ry * _serverHeight.value
                        RemoteAccessibilityService.performDoubleTap(x, y)
                        addServerLog("[$clientIp] 模拟双击", LogType.SUCCESS)
                    }
                }

                command.startsWith("LONG_PRESS:") -> {
                    val coords = command.substringAfter("LONG_PRESS:").split(",")
                    if (coords.size == 2) {
                        val rx = coords[0].toFloatOrNull() ?: return
                        val ry = coords[1].toFloatOrNull() ?: return
                        val x = rx * _serverWidth.value
                        val y = ry * _serverHeight.value
                        RemoteAccessibilityService.performLongPress(x, y)
                        addServerLog("[$clientIp] 模拟长按", LogType.SUCCESS)
                    }
                }

                command.startsWith("DOWN:") -> {
                    val coords = command.substringAfter("DOWN:").split(",")
                    if (coords.size == 2) {
                        val rx = coords[0].toFloatOrNull() ?: return
                        val ry = coords[1].toFloatOrNull() ?: return
                        val x = rx * _serverWidth.value
                        val y = ry * _serverHeight.value
                        RemoteAccessibilityService.handleTouchDown(x, y)
                    }
                }

                command.startsWith("MOVE:") -> {
                    val coords = command.substringAfter("MOVE:").split(",")
                    if (coords.size == 2) {
                        val rx = coords[0].toFloatOrNull() ?: return
                        val ry = coords[1].toFloatOrNull() ?: return
                        val x = rx * _serverWidth.value
                        val y = ry * _serverHeight.value
                        RemoteAccessibilityService.handleTouchMove(x, y)
                    }
                }

                command.startsWith("UP:") -> {
                    RemoteAccessibilityService.handleTouchUp()
                }

                command == "BACK" -> {
                    if (RemoteAccessibilityService.performBack()) {
                        addServerLog("[$clientIp] 模拟返回", LogType.SUCCESS)
                    } else {
                        addServerLog("[$clientIp] 返回失败: 无障碍服务未运行", LogType.WARNING)
                    }
                }

                command == "HOME" -> {
                    if (RemoteAccessibilityService.performHome()) {
                        addServerLog("[$clientIp] 模拟回到主页", LogType.SUCCESS)
                    } else {
                        addServerLog("[$clientIp] 回到主页失败: 无障碍服务未运行", LogType.WARNING)
                    }
                }

                else -> addServerLog("[$clientIp] 未知指令: $command", LogType.WARNING)
            }
        } catch (e: Exception) {
            addServerLog("[$clientIp] 指令处理异常: ${e.message}", LogType.WARNING)
        }
    }


    // ----------------------------------------------------
    // 主控端(控制端)客户端逻辑 (CLIENT (CONTROLLER) LOGIC)
    // ----------------------------------------------------

    /**
     * 开启局域网设备 UDP 探针探测，在后台静默寻找当前在局域网内广播的服务端物理 IP 并且刷洗可选列表
     */
    fun startDiscovery() {
        _discoveredServers.value = emptySet()
        udpListener?.stop()
        udpListener = UdpListener(
            port = NetworkConstants.UDP_PORT,
            onServersUpdated = { serverSet ->
                val oldSet = _discoveredServers.value
                _discoveredServers.value = serverSet

                (serverSet - oldSet).forEach { ip ->
                    addControllerLog("发现局域网可用共控端设备: $ip", LogType.SUCCESS)
                }
                (oldSet - serverSet).forEach { ip ->
                    addControllerLog("局域网设备已离线: $ip", LogType.WARNING)
                }
            },
            onError = { error -> addControllerLog(error, LogType.WARNING) }
        )
        udpListener?.start()
        addControllerLog("已开启局域网探针寻找服务端设备...", LogType.INFO)
    }

    /**
     * 关闭并注销局域网嗅探 UDP 探针
     */
    fun stopDiscovery() {
        udpListener?.stop()
        udpListener = null
    }

    /**
     * 手动输入并在状态中保存配对的服务端 IP 地址
     * @param ip 用户手工键入的 IPv4 字符
     */
    fun setManualIp(ip: String) {
        _manualIpField.value = ip
    }

    /**
     * 按下自动扫描到的服务端设备徽章(Chip)直接选定此目标主机
     * @param ip 被选定并自动填充的目标服务端物理 IP
     */
    fun selectServer(ip: String) {
        _manualIpField.value = ip
    }

    fun handleReceivedData(payload: ByteArray) {
        try {
            val buffer = java.nio.ByteBuffer.wrap(payload)
            val type = buffer.get().toInt()

            when (type) {
                NetworkConstants.TYPE_VIDEO -> { // TYPE_VIDEO
                    val flags = buffer.int
                    val pts = buffer.long
                    val data = ByteArray(buffer.remaining())
                    buffer.get(data)
                    val frame = Triple(data, flags, pts)
                    _encodedFrameFlow.tryEmit(frame)
                    _hasFrameReceived.value = true
                }

                NetworkConstants.TYPE_SIZE -> { // TYPE_SIZE
                    val message = String(
                        ByteArray(buffer.remaining()).apply { buffer.get(this) },
                        Charsets.UTF_8
                    )
                    if (message.startsWith("SIZE:")) {
                        val sizes = message.substringAfter("SIZE:").split(",")
                        if (sizes.size >= 3) {
                            _mirroredWidth.value =
                                sizes[0].toIntOrNull() ?: ViewModelConstants.DEFAULT_WIDTH
                            _mirroredHeight.value =
                                sizes[1].toIntOrNull() ?: ViewModelConstants.DEFAULT_HEIGHT
                            val rot = if (sizes[2].toIntOrNull() == 1) "横屏" else "竖屏"
                            addControllerLog(
                                "同步远端分辨率: ${_mirroredWidth.value}x${_mirroredHeight.value} ($rot)",
                                LogType.INFO
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            addControllerLog("数据解析异常: ${e.message}", LogType.WARNING)
        }
    }

    /**
     * 连接到用户在输入框或选择列表中指定的 TCP 服务端主机节点
     * @param context 加载上下文，以便需要时发起悬浮窗独立受控界面的弹窗与授权启动
     */
    fun connectToSelectedServer() {
        val ip = _manualIpField.value.trim()
        if (ip.isEmpty()) {
            addControllerLog("连接失败: Server IP 不能为空", LogType.WARNING)
            return
        }

        if (!IpValidator.isValidIPv4(ip)) {
            addControllerLog("连接失败: IP 格式无效 [$ip]", LogType.WARNING)
            return
        }

        disconnectFromServer()

        addControllerLog("正在尝试连接服务端: $ip:${NetworkConstants.TCP_PORT} ...", LogType.INFO)
        socketClient = SocketClient(
            serverIp = ip,
            onStateChanged = { state ->
                _connectionState.value = state
                when (state) {
                    ConnectionState.Connected -> addControllerLog("已建立连接", LogType.SUCCESS)
                    ConnectionState.Disconnected -> {
                        addControllerLog("连接已断开", LogType.WARNING)
                        _hasFrameReceived.value = false
                    }

                    else -> {}
                }
            },
            onMessageReceived = { handleReceivedData(it) },
            onError = { error -> addControllerLog(error, LogType.WARNING) }
        )
        socketClient?.connect()
    }

    /**
     * 反向将当前控制设备对应的坐标手势封装成的控制字符串上报给配对被控机器
     * @param actionCommand 操作指令协议串 (例如 TAP:0.5,0.4)
     */
    fun sendClientAction(actionCommand: String) {
        socketClient?.sendCommand(actionCommand)
    }

    /**
     * 主动切断并安全闭合当前的 TCP 主控端网络链路并还原控制状态
     */
    fun disconnectFromServer() {
        socketClient?.disconnect()
        socketClient = null
        _connectionState.value = ConnectionState.Disconnected
        _hasFrameReceived.value = false
    }

    /**
     * 计算并对齐录屏采集的分辨率
     */
    private fun calculateCaptureSize(width: Int, height: Int): Pair<Int, Int> {
        var captureWidth = ((width * ViewModelConstants.CAPTURE_SCALE).toInt() / 16) * 16
        var captureHeight = ((height * ViewModelConstants.CAPTURE_SCALE).toInt() / 16) * 16
        if (captureWidth <= 0) captureWidth = ViewModelConstants.DEFAULT_WIDTH
        if (captureHeight <= 0) captureHeight = ViewModelConstants.DEFAULT_HEIGHT
        return Pair(captureWidth, captureHeight)
    }

    /**
     * 架构在 ViewModel 生命期闭合、切换或彻底销毁时的全量安全清理，规避端口被长期霸占
     */
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
