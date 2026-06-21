package com.example.ui

import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.RemoteAccessibilityService
import com.example.ScreenCaptureService
import com.example.network.ConnectionState
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
    private val _serverWidth = MutableStateFlow(1080)
    private val _serverHeight = MutableStateFlow(2400)

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

    // 在客户端本地缓存最新的 SPS/PPS 编解码配置帧(标志位=2的配置块)，用于解决解码器启动时初始化屏幕信息的问题
    private val _clientCodecConfig = MutableStateFlow<Triple<ByteArray, Int, Long>?>(null)
    val clientCodecConfig: StateFlow<Triple<ByteArray, Int, Long>?> =
        _clientCodecConfig.asStateFlow()

    // 同步镜像的物理参考大屏宽度
    private val _mirroredWidth = MutableStateFlow(1080)
    val mirroredWidth: StateFlow<Int> = _mirroredWidth.asStateFlow()

    // 同步镜像的物理参考大屏高度
    private val _mirroredHeight = MutableStateFlow(2400)
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
    private var cachedCodecConfig: ByteArray? = null

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
        _serverLogs.value = listOf(entry) + _serverLogs.value.take(255)
    }

    /**
     * 向控制端日志队列列表中头插新纪录
     * @param message 日志消息文本内容
     * @param type 日志重要等级类型 [LogType]（正常消息/成功通知/警告）
     */
    fun addControllerLog(message: String, type: LogType = LogType.INFO) {
        val entry = LogEntry(timestamp = getCurrentTimestamp(), message = message, type = type)
        _controllerLogs.value = listOf(entry) + _controllerLogs.value.take(255)
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
    fun startServer(context: Context) {
        if (_isServerRunning.value) return

        viewModelScope.launch {
            val ip = withContext(Dispatchers.IO) { getLocalIpAddress() }
            _serverIp.value = ip

            // 开启 TCP 套接字监听：对准端口 9292
            socketServer = SocketServer(
                port = 9292,
                onClientConnected = { clientIp ->
                    _connectedClients.value = (_connectedClients.value + clientIp).distinct()
                    addServerLog("客户端已连接: $clientIp", LogType.SUCCESS)

                    // 新的客户端连接时，立即向其同步当前服务端的坐标尺寸与微缩流画幅大小
                    /*var captureWidth = (_serverWidth.value * 0.35f).toInt()
                    var captureHeight = (_serverHeight.value * 0.35f).toInt()
                    captureWidth = (captureWidth / 16) * 16
                    captureHeight = (captureHeight / 16) * 16
                    if (captureWidth <= 0) captureWidth = 360
                    if (captureHeight <= 0) captureHeight = 640

                    val sizeMsg = "SIZE:$_serverWidth.value,$_serverHeight.value,$captureWidth,$captureHeight".toByteArray(Charsets.UTF_8)
                    val buffer = java.nio.ByteBuffer.allocate(1 + sizeMsg.size)
                    buffer.put(3.toByte())
                    buffer.put(sizeMsg)

                    socketServer?.broadcastData(buffer.array())*/

                    updateScreenSize()

                    // 立即将本地缓存的最近一次 SPS/PPS 首配置帧灌送给刚连接进来的客户端使其可以立即初始化解码器
                    cachedCodecConfig?.let { configMsg ->
                        socketServer?.broadcastData(configMsg)
                    }
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

            // 建立局域网 UDP 定时广播器：面向端口 9293
            udpBroadcaster = UdpBroadcaster(serverIp = ip, port = 9293)
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

        var captureWidth = ((width * 0.8).toInt() / 16) * 16
        var captureHeight = ((height * 0.8).toInt() / 16) * 16
        if (captureWidth <= 0) captureWidth = 1080
        if (captureHeight <= 0) captureHeight = 2400

        addServerLog(
            "本机录屏分辨率初始化完成: ${width}x${height} (视频流分辨率: ${captureWidth}x${captureHeight}), 旋转状态: $rotation",
            LogType.INFO
        )
        //socketServer?.broadcastMessage("SIZE:$width,$height,$captureWidth,$captureHeight")

        // 构造二进制包：1 byte(类型:3代表SIZE) + N bytes(字符串数据)
        // 修改协议串：增加第五个参数 rotation
        val sizeMsg =
            "SIZE:$captureWidth,$captureHeight,$rotation".toByteArray(Charsets.UTF_8)
        val buffer = java.nio.ByteBuffer.allocate(1 + sizeMsg.size)
        buffer.put(3.toByte()) // TYPE_SIZE
        buffer.put(sizeMsg)

        socketServer?.broadcastData(buffer.array()) // 注意这里方法名改为了 broadcastData
    }


    // 修改方法签名，将 base64Data: String 改为 data: ByteArray
    fun onEncodedFrameCaptured(data: ByteArray, flags: Int, presentationTimeUs: Long) {
        // 构造二进制包：1 byte(类型:2代表视频) + 4 bytes(Flags) + 8 bytes(PTS) + N bytes(视频数据)
        val buffer = java.nio.ByteBuffer.allocate(1 + 4 + 8 + data.size)
        buffer.put(2.toByte())
        buffer.putInt(flags)
        buffer.putLong(presentationTimeUs)
        buffer.put(data)

        val packet = buffer.array()
        if ((flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            cachedCodecConfig = packet // 缓存 SPS/PPS
        }
        socketServer?.broadcastData(packet)
    }

    /**
     * 接收并解析控制端传输上来的模拟反控指令
     * @param context 上下文
     * @param clientIp 来源端的 IP 地址
     * @param command 命令参数协议串
     */
    private fun handleReceivedCommand(context: Context, clientIp: String, command: String) {
        when {
            command.startsWith("TAP:") -> {
                try {
                    val coords = command.substringAfter("TAP:").split(",")
                    if (coords.size == 2) {
                        val rx = coords[0].toFloatOrNull() ?: return
                        val ry = coords[1].toFloatOrNull() ?: return

                        // 将相对百分比映射还原为服务端物理屏幕的大屏精确实际点击像素位置
                        val originalX = rx * _serverWidth.value
                        val originalY = ry * _serverHeight.value

                        val injected = RemoteAccessibilityService.performTap(originalX, originalY)
                        if (injected) {
                            addServerLog(
                                "成功模拟点击坐标: (${originalX.toInt()}, ${originalY.toInt()})",
                                LogType.SUCCESS
                            )
                        } else {
                            addServerLog(
                                "模拟点击失败: 辅助功能运行状态 [${RemoteAccessibilityService.isServiceRunning()}]",
                                LogType.WARNING
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "解析 TAP 触控命令由于异常崩溃: ${e.message}")
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
                                addServerLog(
                                    "成功模拟滑动: (${sx.toInt()}, ${sy.toInt()}) -> (${ex.toInt()}, ${ey.toInt()})",
                                    LogType.SUCCESS
                                )
                            } else {
                                addServerLog("模拟滑动失败: 轴值映射验证失败", LogType.WARNING)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "解析 SWIPE 滑动命令错误: ${e.message}")
                }
            }
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

            if (type == 2) { // TYPE_VIDEO：处理视频流
                val flags = buffer.int
                val presentationTimeUs = buffer.long
                val data = ByteArray(buffer.remaining())
                buffer.get(data)

                val frameTriple = Triple(data, flags, presentationTimeUs)
                if ((flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    _clientCodecConfig.value = frameTriple
                }

                _encodedFrameFlow.tryEmit(frameTriple)
                _hasFrameReceived.value = true

            } else if (type == 3) { // TYPE_SIZE：处理分辨率配置
                val msgBytes = ByteArray(buffer.remaining())
                buffer.get(msgBytes)
                val message = String(msgBytes, Charsets.UTF_8)
                if (message.startsWith("SIZE:")) {
                    val sizes = message.substringAfter("SIZE:").split(",")
                    if (sizes.size >= 3) {
                        _mirroredWidth.value = sizes[0].toIntOrNull() ?: 1080
                        _mirroredHeight.value = sizes[1].toIntOrNull() ?: 2400
                        val rotation = sizes[2].toIntOrNull() ?: 0
                        addControllerLog(
                            "同步远端屏幕状态: 镜像宽:${_mirroredWidth.value}  镜像高:${_mirroredHeight.value} " +
                                    " ${if (rotation == 1) "横屏" else "竖屏"}",
                            LogType.INFO
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析二进制包失败: ${e.message}")
        }
    }

    /**
     * 连接到用户在输入框或选择列表中指定的 TCP 服务端主机节点
     * @param context 加载上下文，以便需要时发起悬浮窗独立受控界面的弹窗与授权启动
     */
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
                        _hasFrameReceived.value = false
                    }

                    else -> {}
                }
            },
            onMessageReceived = { payload ->
                handleReceivedData(payload)
            }
        )
        socketClient?.connect()
    }

    /**
     * 反向将当前控制设备对应的坐标手势封装成的控制字符串上报给配对被控机器
     * @param actionCommand 操作指令协议串 (例如 TAP:0.5,0.4)
     */
    fun sendClientAction(actionCommand: String) {
        val client = socketClient
        if (client != null && _connectionState.value == ConnectionState.Connected) {
            client.sendCommand(actionCommand)
        }
    }

    /**
     * 主动切断并安全闭合当前的 TCP 主控端网络链路并还原控制状态
     */
    fun disconnectFromServer() {
        socketClient?.disconnect()
        socketClient = null
        _connectionState.value = ConnectionState.Disconnected
        _hasFrameReceived.value = false
        _clientCodecConfig.value = null
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
