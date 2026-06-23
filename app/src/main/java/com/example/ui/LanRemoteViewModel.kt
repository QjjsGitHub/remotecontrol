package com.example.ui

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ScreenCaptureService
import com.example.data.model.*
import com.example.data.repository.RemoteControlRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel 相关常量
 */
private object ViewModelConstants {
    /** 默认视频流宽度 */
    const val DEFAULT_WIDTH = 1080

    /** 默认视频流高度 */
    const val DEFAULT_HEIGHT = 2400
}

@HiltViewModel
class LanRemoteViewModel @Inject constructor(
    private val repository: RemoteControlRepository
) : ViewModel() {

    companion object {
        const val TAG = "LanRemoteViewModel"
    }

    // 被控端(服务端)运行状态
    val isServerRunning: StateFlow<Boolean> = repository.serverState
        .map { it is ServerState.Running }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // 本机在局域网中的物理 IPv4 地址
    val serverIp: StateFlow<String> = repository.serverIp

    // 当前已接入服务端的控制端客户端 IP 列表
    val connectedClients: StateFlow<List<ClientInfo>> = repository.connectedClients

    // 被控端本地系统与网络交互日志
    val serverLogs: StateFlow<List<LogEntry>> = repository.serverLogs

    // 控制端(客户端)状态：连接、正在连接、未连接
    val connectionState: StateFlow<ConnectionState> = repository.clientConnectionState

    // 局域网内通过 UDP 探针寻找到的多路服务端地址集
    val discoveredServers: StateFlow<Set<ServerInfo>> = repository.discoveredServers

    // 控制端手动输入的远端 IP 地址字段
    val manualIpField: StateFlow<String> = repository.manualIpField

    // 控制端交互行为与流状态控制日志
    val controllerLogs: StateFlow<List<LogEntry>> = repository.clientLogs

    // 标志当前控制端是否接收到了至少一个有效的 H264 视频流编码切片帧
    val hasFrameReceived: StateFlow<Boolean> = repository.hasFrameReceived

    // 未合并累积的视频流数据切片热流，提供给悬浮窗底层的 MediaCodec 硬件解码器直接读取
    val encodedFrameFlow: SharedFlow<Triple<ByteArray, Int, Long>> = repository.videoFrames
        .map { Triple(it.data, it.flags, it.pts) }
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000))


    // 同步镜像的物理参考大屏宽度
    val mirroredWidth: StateFlow<Int> = repository.mirroredWidth

    // 同步镜像的物理参考大屏高度
    val mirroredHeight: StateFlow<Int> = repository.mirroredHeight

    // 悬浮窗的缩放比例状态流
    val floatingScaleMultiplier: StateFlow<Float> = repository.floatingScaleMultiplier

    /**
     * 更新全局悬浮窗的缩放比例
     */
    fun updateFloatingScaleMultiplier(value: Float) {
        repository.updateFloatingScaleMultiplier(value)
    }

    init {
        // 默认初始化即开启局域网探针
        startDiscovery()
    }

    fun addServerLog(message: String, type: LogType = LogType.INFO) {
        repository.addServerLog(message, type)
    }

    fun addControllerLog(message: String, type: LogType = LogType.INFO) {
        repository.addClientLog(message, type)
    }

    fun clearServerLogs() {
        repository.clearServerLogs()
    }

    fun clearControllerLogs() {
        repository.clearClientLogs()
    }

    // ----------------------------------------------------
    // 被控端服务端控制管理逻辑 (SERVER CONTROL LOGIC)
    // ----------------------------------------------------

    fun startServer() {
        viewModelScope.launch {
            repository.startServer()
        }
    }

    fun stopServer(context: Context?) {
        viewModelScope.launch {
            repository.stopServer()
            // 重置并销毁前台屏幕像素录制采集服务
            context?.let { ctx ->
                try {
                    ctx.stopService(Intent(ctx, ScreenCaptureService::class.java))
                } catch (e: Exception) {
                    Log.e(TAG, "注销视频采集服务异常: ${e.message}")
                }
            }
        }
    }

    // ----------------------------------------------------
    // 主控端(控制端)客户端逻辑 (CLIENT (CONTROLLER) LOGIC)
    // ----------------------------------------------------

    fun startDiscovery() {
        viewModelScope.launch {
            repository.startDiscovery()
        }
    }

    fun stopDiscovery() {
        viewModelScope.launch {
            repository.stopDiscovery()
        }
    }

    fun setManualIp(ip: String) {
        repository.setManualIp(ip)
    }

    fun selectServer(serverInfo: ServerInfo) {
        repository.selectServer(serverInfo)
    }

    /**
     * 旧版兼容：接收字符串 IP
     */
    fun selectServer(ip: String) {
        repository.setManualIp(ip)
    }

    fun connectToSelectedServer() {
        val ip = manualIpField.value.trim()
        if (ip.isEmpty()) {
            addControllerLog("连接失败: Server IP 不能为空", LogType.WARNING)
            return
        }

        viewModelScope.launch {
            repository.connectToServer(ServerInfo(ip))
        }
    }

    fun sendClientAction(actionCommand: String) {
        viewModelScope.launch {
            repository.sendCommand(actionCommand)
        }
    }

    fun disconnectFromServer() {
        viewModelScope.launch {
            repository.disconnect()
        }
    }

    override fun onCleared() {
        viewModelScope.launch {
            repository.stopServer()
            repository.stopDiscovery()
            repository.disconnect()
        }
        super.onCleared()
    }
}
