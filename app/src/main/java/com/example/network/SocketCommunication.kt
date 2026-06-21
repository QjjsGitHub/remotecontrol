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
import java.net.SocketTimeoutException
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

/**
 * 网络通信常量
 */
object NetworkConstants {
    /** TCP 服务端默认端口 */
    const val TCP_PORT = 9292

    /** UDP 广播端口 */
    const val UDP_PORT = 9293

    /** 最大数据包大小 (10MB) - 防止 OOM 攻击 */
    const val MAX_PACKET_SIZE = 10 * 1024 * 1024

    /** TCP 连接超时时间 (毫秒) */
    const val CONNECT_TIMEOUT_MS = 3000L

    /** UDP 广播间隔 (毫秒) */
    const val BROADCAST_INTERVAL_MS = 2000L

    /** UDP 缓冲区大小 */
    const val UDP_BUFFER_SIZE = 1024

    /** 数据包类型标识 */
    const val TYPE_VIDEO = 2
    const val TYPE_SIZE = 3

    /** UDP 广播前缀 */
    const val UDP_BROADCAST_PREFIX = "LAN_REMOTE_SERVER:"
}

class SocketServer(
    val port: Int = NetworkConstants.TCP_PORT,
    private val onClientConnected: (String) -> Unit,
    private val onClientDisconnected: (String) -> Unit,
    private val onCommandReceived: (String, String) -> Unit,
    private val onError: (String) -> Unit = {}
) {
    private val TAG = "SocketServer"
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeClients = ConcurrentHashMap<String, ClientHandler>()

    inner class ClientHandler(val socket: Socket, val clientIp: String) {
        private val frameChannel =
            kotlinx.coroutines.channels.Channel<ByteArray>(capacity = kotlinx.coroutines.channels.Channel.CONFLATED)

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
                    if (isRunning && socket.isConnected) {
                        onError("向客户端 [$clientIp] 发送数据失败: ${e.message}")
                    }
                } finally {
                    disconnect()
                }
            }

            // 接收协程 (客户端指令 -> 服务端)
            scope.launch(Dispatchers.IO) {
                try {
                    val inputStream = java.io.DataInputStream(socket.getInputStream())
                    while (isRunning) {
                        val length = inputStream.readInt()
                        Log.d("手势", "length：$length")
                        if (length in 1..NetworkConstants.MAX_PACKET_SIZE) {
                            val payload = ByteArray(length)
                            inputStream.readFully(payload)
                            val message = String(payload, Charsets.UTF_8)
                            withContext(Dispatchers.Main) {
                                onCommandReceived(clientIp, message)
                            }
                        } else {
                            onError("客户端 [$clientIp] 发送异常大小的数据包: $length 字节")
                            // 核心：一旦流污染（出现异常长度），必须断开连接重新对齐
                            disconnect()
                        }
                    }
                } catch (e: Exception) {
                    if (isRunning && socket.isConnected) {
                        onError("接收客户端 [$clientIp] 指令失败: ${e.message}")
                    }
                } finally {
                    disconnect()
                }
            }
        }

        fun sendData(data: ByteArray) {
            frameChannel.trySend(data)
        }

        fun disconnect() {
            try {
                if (!socket.isClosed) socket.close()
            } catch (_: Exception) {
            }
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
                Log.i(TAG, "服务端已启动，监听端口: $port")
                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    val clientIp = socket.inetAddress.hostAddress ?: "未知IP"
                    val handler = ClientHandler(socket, clientIp)
                    activeClients[clientIp] = handler
                    launch(Dispatchers.Main) { onClientConnected(clientIp) }
                    handler.start()
                    Log.d(TAG, "新客户端连接: $clientIp")
                }
            } catch (e: Exception) {
                if (isRunning) {
                    onError("服务端运行异常: ${e.message}")
                }
            } finally {
                if (isRunning) stop()
            }
        }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        activeClients.values.forEach { it.disconnect() }
        activeClients.clear()
        scope.coroutineContext.cancelChildren()
        Log.i(TAG, "服务端已停止")
    }
}

class SocketClient(
    val serverIp: String,
    val port: Int = NetworkConstants.TCP_PORT,
    private val onStateChanged: (ConnectionState) -> Unit,
    private val onMessageReceived: (ByteArray) -> Unit,
    private val onError: (String) -> Unit = {}
) {
    private val TAG = "SocketClient"
    private var socket: Socket? = null
    private var outputStream: java.io.DataOutputStream? = null
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 核心改进：使用 Channel 序列化指令发送，防止并发写入导致 TCP 流污染
    private val commandChannel = kotlinx.coroutines.channels.Channel<String>(capacity = 64)

    fun connect() {
        if (isRunning) return
        isRunning = true
        onStateChanged(ConnectionState.Connecting)

        scope.launch {
            try {
                val targetSocket = Socket()
                targetSocket.connect(
                    InetSocketAddress(serverIp, port),
                    NetworkConstants.CONNECT_TIMEOUT_MS.toInt()
                )
                socket = targetSocket
                outputStream = java.io.DataOutputStream(targetSocket.getOutputStream())

                // 【关键】启动专门的写协程，保证同一时间只有一个协程在操作 OutputStream
                launch(Dispatchers.IO) {
                    try {
                        for (command in commandChannel) {
                            val out = outputStream ?: break
                            val bytes = command.toByteArray(Charsets.UTF_8)
                            out.writeInt(bytes.size)
                            out.write(bytes)
                            out.flush()
                        }
                    } catch (e: Exception) {
                        if (isRunning) onError("发送指令流中断: ${e.message}")
                    } finally {
                        disconnectInternal()
                    }
                }

                withContext(Dispatchers.Main) { onStateChanged(ConnectionState.Connected) }
                Log.i(TAG, "已成功连接到服务端: $serverIp:$port")

                val inputStream = java.io.DataInputStream(targetSocket.getInputStream())
                while (isRunning) {
                    val length = inputStream.readInt()
                    if (length in 1..NetworkConstants.MAX_PACKET_SIZE) {
                        val payload = ByteArray(length)
                        inputStream.readFully(payload)
                        withContext(Dispatchers.Main) {
                            onMessageReceived(payload)
                        }
                    } else {
                        onError("接收到异常大小的数据包: $length 字节")
                        disconnectInternal()
                    }
                }
            } catch (_: SocketTimeoutException) {
                onError("连接超时: 无法连接到 $serverIp")
                withContext(Dispatchers.Main) { onStateChanged(ConnectionState.Disconnected) }
            } catch (e: Exception) {
                if (isRunning) {
                    onError("连接异常: ${e.message}")
                    withContext(Dispatchers.Main) { onStateChanged(ConnectionState.Disconnected) }
                }
            } finally {
                isRunning = false
                disconnectInternal()
            }
        }
    }

    /**
     * 向服务端发送控制指令。
     * 采用非阻塞的 Channel 模型，确保高频手势下指令顺序发送且不发生字节重叠。
     */
    fun sendCommand(command: String) {
        if (isRunning && socket?.isConnected == true) {
            commandChannel.trySend(command)
        }
    }

    fun disconnect() {
        if (!isRunning) return
        isRunning = false
        scope.launch { disconnectInternal() }
    }

    private suspend fun disconnectInternal() {
        try {
            if (socket?.isClosed == false) {
                withContext(Dispatchers.IO) {
                    socket?.close()
                }
            }
        } catch (_: Exception) {
        }
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
 * @property port 目标广播广播侦听 UDP 端口号
 */
class UdpBroadcaster(
    private val serverIp: String,
    private val port: Int = NetworkConstants.UDP_PORT,
    private val onError: (String) -> Unit = {}
) {
    private var socket: DatagramSocket? = null
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * 开启并以固定间隔循环发送 UDP 广播封包
     */
    fun start() {
        if (isRunning) return
        isRunning = true
        scope.launch {
            try {
                socket = DatagramSocket().apply {
                    broadcast = true
                }
                val contents = "${NetworkConstants.UDP_BROADCAST_PREFIX}$serverIp".toByteArray()
                val packet = DatagramPacket(
                    contents,
                    contents.size,
                    InetAddress.getByName("255.255.255.255"),
                    port
                )
                while (isRunning) {
                    socket?.send(packet)
                    delay(NetworkConstants.BROADCAST_INTERVAL_MS.milliseconds)
                }
            } catch (e: Exception) {
                if (isRunning) {
                    onError("UDP 广播异常: ${e.message}")
                }
            }
        }
    }

    /**
     * 关闭并停止发送服务广播
     */
    fun stop() {
        if (!isRunning) return
        isRunning = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        scope.coroutineContext.cancelChildren()
    }
}

/**
 * UDP 设备侦听寻迹器 (UdpListener)
 * 供控制端(客户端)在后台启动，用于全网段嗅探并监听是否有存活的屏幕直播服务端正在广播声明，
 * 以便实时刷新”发现同Wi-Fi网络服务端”列表。
 *
 * @property port 服务端 UDP 广播信道端口
 * @property onServersUpdated 当局域网发现的服务端列表发生变化（增加或过期移除）时的回调
 */
class UdpListener(
    private val port: Int = NetworkConstants.UDP_PORT,
    private val onServersUpdated: (Set<String>) -> Unit,
    private val onError: (String) -> Unit = {}
) {
    private val TAG = "UdpListener"
    private var socket: DatagramSocket? = null
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 追踪每个发现的 IP 及其最后活跃时间戳
    private val lastSeenMap = ConcurrentHashMap<String, Long>()

    /**
     * 启动 UDP 物理监听探针
     */
    fun start() {
        if (isRunning) return
        isRunning = true

        // 接收广播的协程
        scope.launch {
            try {
                socket = DatagramSocket(port).apply {
                    reuseAddress = true
                    soTimeout = 2000
                }
                val buffer = ByteArray(NetworkConstants.UDP_BUFFER_SIZE)
                while (isRunning) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        withContext(Dispatchers.IO) {
                            socket?.receive(packet)
                        }
                        val message = String(packet.data, 0, packet.length).trim()
                        if (message.startsWith(NetworkConstants.UDP_BROADCAST_PREFIX)) {
                            val ip = message.substringAfter(NetworkConstants.UDP_BROADCAST_PREFIX)
                            val now = System.currentTimeMillis()
                            val isNew = !lastSeenMap.containsKey(ip)
                            lastSeenMap[ip] = now

                            if (isNew) {
                                withContext(Dispatchers.Main) {
                                    onServersUpdated(lastSeenMap.keys.toSet())
                                }
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    onError("UDP 监听异常: ${e.message}")
                }
            }
        }

        // 定时清理过期设备的协程 (TTL 机制)
        scope.launch {
            while (isRunning) {
                delay((NetworkConstants.BROADCAST_INTERVAL_MS * 2).milliseconds) // 每 4 秒检查一次
                val now = System.currentTimeMillis()
                var hasRemoved = false
                val iterator = lastSeenMap.entries.iterator()

                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    // 如果超过 3 次广播周期 (约 6-7 秒) 没收到包，认为已下线
                    if (now - entry.value > NetworkConstants.BROADCAST_INTERVAL_MS * 3 + 1000) {
                        Log.i(TAG, "服务端 IP 已过期移除: ${entry.key}")
                        iterator.remove()
                        hasRemoved = true
                    }
                }

                if (hasRemoved) {
                    withContext(Dispatchers.Main) {
                        onServersUpdated(lastSeenMap.keys.toSet())
                    }
                }
            }
        }
    }

    /**
     * 释放并关闭局域网嗅探 UDP 无线网卡套接字句柄
     */
    fun stop() {
        if (!isRunning) return
        isRunning = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        lastSeenMap.clear()
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
