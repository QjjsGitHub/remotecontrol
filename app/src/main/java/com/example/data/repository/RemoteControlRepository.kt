package com.example.data.repository

import com.example.data.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 远程控制Repository接口
 * 定义了所有与远程控制相关的数据操作
 */
interface RemoteControlRepository {

    // ========== 服务器端操作 ==========

    /** 服务器运行状态 */
    val serverState: StateFlow<ServerState>

    /** 服务器IP */
    val serverIp: StateFlow<String>

    /** 连接的客户端列表 */
    val connectedClients: StateFlow<List<ClientInfo>>

    /**
     * 启动服务器
     */
    suspend fun startServer(): Result<Unit>

    /**
     * 停止服务器
     */
    suspend fun stopServer(): Result<Unit>

    /**
     * 广播视频帧
     */
    suspend fun broadcastVideoFrame(frame: VideoFrame)

    /**
     * 广播视频帧 (直接使用原始参数)
     */
    suspend fun broadcastVideoFrame(data: ByteArray, flags: Int, pts: Long)

    /**
     * 广播屏幕尺寸信息
     */
    suspend fun broadcastScreenSize(size: ScreenSize)

    /**
     * 处理从客户端接收到的命令
     * @param command 远程控制命令
     */
    suspend fun handleClientCommand(command: RemoteCommand)

    // ========== 客户端操作 ==========

    /** 客户端连接状态 */
    val clientConnectionState: StateFlow<ConnectionState>

    /** 发现的服务器列表 */
    val discoveredServers: StateFlow<Set<ServerInfo>>

    /** 接收到的视频帧流 */
    val videoFrames: Flow<VideoFrame>

    /** 镜像屏幕宽度 */
    val mirroredWidth: StateFlow<Int>

    /** 镜像屏幕高度 */
    val mirroredHeight: StateFlow<Int>

    /** 是否接收到过视频帧 */
    val hasFrameReceived: StateFlow<Boolean>

    /** 悬浮窗缩放比例 */
    val floatingScaleMultiplier: StateFlow<Float>

    /** 手动输入的IP */
    val manualIpField: StateFlow<String>

    /**
     * 启动设备发现
     */
    suspend fun startDiscovery()

    /**
     * 停止设备发现
     */
    suspend fun stopDiscovery()

    /**
     * 连接到服务器
     */
    suspend fun connectToServer(serverInfo: ServerInfo): Result<Unit>

    /**
     * 断开连接
     */
    suspend fun disconnect(): Result<Unit>

    /**
     * 发送控制命令
     */
    suspend fun sendCommand(command: String): Result<Unit>

    /**
     * 手动输入服务器IP
     */
    fun setManualIp(ip: String)

    /**
     * 选择发现的服务器
     */
    fun selectServer(serverInfo: ServerInfo)

    /**
     * 更新悬浮窗缩放比例
     */
    fun updateFloatingScaleMultiplier(value: Float)

    // ========== 日志操作 ==========

    /** 服务端日志流 */
    val serverLogs: StateFlow<List<LogEntry>>

    /** 客户端日志流 */
    val clientLogs: StateFlow<List<LogEntry>>

    /** 添加服务端日志 */
    fun addServerLog(message: String, type: LogType = LogType.INFO)

    /** 添加客户端日志 */
    fun addClientLog(message: String, type: LogType = LogType.INFO)

    /** 清空服务端日志 */
    fun clearServerLogs()

    /** 清空客户端日志 */
    fun clearClientLogs()

    // ========== 配置操作 ==========

    /** 设置服务端屏幕尺寸 */
    fun setServerScreenSize(width: Int, height: Int)

    /** 获取服务端屏幕尺寸 */
    fun getServerScreenSize(): Pair<Int, Int>
}