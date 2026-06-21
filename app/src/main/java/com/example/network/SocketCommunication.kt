package com.example.network

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

/**
 * 远程连接的网络状态枚举
 */
enum class ConnectionState {
    /** 未连接状态 */
    Disconnected,
    /** 正在尝试连接中 */
    Connecting,
    /** 已成功建立连接 */
    Connected
}

class SocketServer(
    val port: Int = 9292,
    private val onClientConnected: (String) -> Unit,
    private val onClientDisconnected: (String) -> Unit,
    private val onCommandReceived: (String, String) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeClients = ConcurrentHashMap<String, ClientHandler>()

    inner class ClientHandler(val socket: Socket, val clientIp: String) {
        // Channel.CONFLATED：如果网络堵塞导致发送慢，新来的视频帧会直接覆盖旧帧，天然解决画面延迟累积问题
        private val frameChannel = kotlinx.coroutines.channels.Channel<ByteArray>(capacity = kotlinx.coroutines.channels.Channel.CONFLATED)

        fun start() {
            // 发送协程 (视频流 -> 客户端)
            scope.launch(Dispatchers.IO) {
                try {
                    val outputStream = java.io.DataOutputStream(socket.getOutputStream())
                    for (data in frameChannel) {
                        outputStream.writeInt(data.size)
                        outputStream.write(data)
                        outputStream.flush()
                    }
                } catch (e: Exception) {
                    disconnect()
                }
            }

            // 接收协程 (客户端指令 -> 服务端)
            scope.launch(Dispatchers.IO) {
                try {
                    val inputStream = java.io.DataInputStream(socket.getInputStream())
                    while (isRunning) {
                        val length = inputStream.readInt()
                        if (length in 1..10485760) { // 限制最大 10MB 防止 OOM
                            val payload = ByteArray(length)
                            inputStream.readFully(payload)
                            val message = String(payload, Charsets.UTF_8)
                            withContext(Dispatchers.Main) {
                                onCommandReceived(clientIp, message)
                            }
                        }
                    }
                } catch (e: Exception) {
                    disconnect()
                }
            }
        }

        fun sendData(data: ByteArray) {
            frameChannel.trySend(data)
        }

        fun disconnect() {
            try { socket.close() } catch (e: Exception) {}
            activeClients.remove(clientIp)
            frameChannel.close()
            scope.launch(Dispatchers.Main) { onClientDisconnected(clientIp) }
        }
    }

    fun broadcastData(data: ByteArray) {
        activeClients.values.forEach { it.sendData(data) }
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        scope.launch {
            try {
                serverSocket = ServerSocket(port).apply { reuseAddress = true }
                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    val clientIp = socket.inetAddress.hostAddress ?: "未知IP"
                    val handler = ClientHandler(socket, clientIp)
                    activeClients[clientIp] = handler
                    launch(Dispatchers.Main) { onClientConnected(clientIp) }
                    handler.start()
                }
            } catch (e: Exception) {}
        }
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (e: Exception) {}
        serverSocket = null
        activeClients.values.forEach { it.disconnect() }
        activeClients.clear()
        scope.coroutineContext.cancelChildren()
    }
}

class SocketClient(
    val serverIp: String,
    val port: Int = 9292,
    private val onStateChanged: (ConnectionState) -> Unit,
    private val onMessageReceived: (ByteArray) -> Unit
) {
    private var socket: Socket? = null
    private var outputStream: java.io.DataOutputStream? = null
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun connect() {
        if (isRunning) return
        isRunning = true
        onStateChanged(ConnectionState.Connecting)

        scope.launch {
            try {
                val targetSocket = Socket()
                targetSocket.connect(InetSocketAddress(serverIp, port), 3000)
                socket = targetSocket
                outputStream = java.io.DataOutputStream(targetSocket.getOutputStream())

                withContext(Dispatchers.Main) { onStateChanged(ConnectionState.Connected) }

                val inputStream = java.io.DataInputStream(targetSocket.getInputStream())
                while (isRunning) {
                    val length = inputStream.readInt()
                    if (length in 1..10485760) {
                        val payload = ByteArray(length)
                        inputStream.readFully(payload) // 阻塞直到完整读完一帧
                        withContext(Dispatchers.Main) {
                            onMessageReceived(payload)
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onStateChanged(ConnectionState.Disconnected) }
            } finally {
                isRunning = false
                disconnectInternal()
            }
        }
    }

    // 客户端发送控制指令依然可以是 String，但底层包装成二进制包发送
    fun sendCommand(command: String): Boolean {
        val out = outputStream
        if (out != null && socket?.isConnected == true) {
            scope.launch {
                try {
                    val bytes = command.toByteArray(Charsets.UTF_8)
                    out.writeInt(bytes.size)
                    out.write(bytes)
                    out.flush()
                } catch (e: Exception) {}
            }
            return true
        }
        return false
    }

    fun disconnect() {
        isRunning = false
        scope.launch { disconnectInternal() }
    }

    private suspend fun disconnectInternal() {
        try { socket?.close() } catch (e: Exception) {}
        socket = null
        outputStream = null
        withContext(Dispatchers.Main) { onStateChanged(ConnectionState.Disconnected) }
    }
}


/**
 * UDP 服务广播器 (UdpBroadcaster)
 * 服务端在开启后用来连续不断地向局域网广播自己的存在信息，便于控制端实现一键自动搜寻与傻瓜式一键填入配对。
 *
 * @property serverIp 本机在局域网配发获得的物理 IP 节点地址
 * @property port 目标广播广播侦听 UDP 端口号，默认 9293
 */
class UdpBroadcaster(
    private val serverIp: String,
    private val port: Int = 9293
) {
    private var socket: DatagramSocket? = null
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * 开启并以两秒为调度间隔，循环发送 UDP 广播封包
     */
    fun start() {
        if (isRunning) return
        isRunning = true
        scope.launch {
            try {
                socket = DatagramSocket().apply {
                    broadcast = true
                }
                val contents = "LAN_REMOTE_SERVER:$serverIp".toByteArray()
                val packet = DatagramPacket(
                    contents,
                    contents.size,
                    InetAddress.getByName("255.255.255.255"),
                    port
                )
                while (isRunning) {
                    socket?.send(packet)
                    delay(2000.milliseconds) // 每两秒向全网投递一次自身节点标识
                }
            } catch (e: Exception) {
                Log.e("UdpBroadcaster", "广播群发捕获异常: ${e.message}")
            }
        }
    }

    /**
     * 关闭并停止发送服务广播
     */
    fun stop() {
        isRunning = false
        try {
            socket?.close()
        } catch (e: Exception) {}
        socket = null
        scope.coroutineContext.cancelChildren()
    }
}

/**
 * UDP 设备侦听寻迹器 (UdpListener)
 * 供控制端(客户端)在后台启动，用于全网段嗅探并监听是否有存活的屏幕直播服务端正在广播声明，
 * 以便实时刷新“发现同Wi-Fi网络服务端”列表。
 *
 * @property port 服务端 UDP 广播信道端口，默认 9293
 * @property onServerDiscovered 当探测并确立监听到某个具体的被群控端 IP 时的发现通知回调
 */
class UdpListener(
    private val port: Int = 9293,
    private val onServerDiscovered: (String) -> Unit
) {
    private var socket: DatagramSocket? = null
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * 启动 UDP 物理监听探针
     */
    fun start() {
        if (isRunning) return
        isRunning = true
        scope.launch {
            try {
                socket = DatagramSocket(port).apply {
                    reuseAddress = true
                }
                val buffer = ByteArray(1024)
                while (isRunning) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    withContext(Dispatchers.IO) {
                        socket?.receive(packet)
                    }
                    val message = String(packet.data, 0, packet.length).trim()
                    if (message.startsWith("LAN_REMOTE_SERVER:")) {
                        val ip = message.substringAfter("LAN_REMOTE_SERVER:")
                        withContext(Dispatchers.Main) {
                            onServerDiscovered(ip)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("UdpListener", "全网段寻迹接收探针挂起/终止: ${e.message}")
            }
        }
    }

    /**
     * 释放并关闭局域网嗅探 UDP 无线网卡套接字句柄
     */
    fun stop() {
        isRunning = false
        try {
            socket?.close()
        } catch (e: Exception) {}
        socket = null
        scope.coroutineContext.cancelChildren()
    }
}

/**
 * 遍历本机所有的活动网络接口，筛选获取可用于局域网通信的真实 IPv4 物理网卡地址
 * @return 真实 IP。如寻找失败或离线默认回退到 "127.0.0.1" 环回接口
 */
fun getLocalIpAddress(): String {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (networkInterface.isLoopback || !networkInterface.isUp) continue
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (!address.isLoopbackAddress && address is Inet4Address) {
                    return address.hostAddress ?: continue
                }
            }
        }
    } catch (e: Exception) {
        Log.e("NetworkUtils", "尝试定位本地网卡 IPv4 失败: ${e.message}")
    }
    return "127.0.0.1"
}
