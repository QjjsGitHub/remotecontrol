package com.example.network

import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.*
import java.util.concurrent.ConcurrentHashMap

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
 * TCP 通信服务端 (SocketServer)
 * 托管在被控制端(服务端)上运行，负责接收并处理控制端发送的模拟点击/滑动等操作指令，
 * 同时向已连接的控制端实时广播当前屏幕录屏得到的视频数据编码切片。
 *
 * @property port 服务端监听的本地TCP端口号，默认9292
 * @property onClientConnected 当有新的控制端(客户端)通过TCP连接进来时的回调函数，入参为客户端IP地址
 * @property onClientDisconnected 当有控制端(客户端)连接断开时的回调函数，入参为客户端IP地址
 * @property onCommandReceived 当收到控制端发送过来的控制指令时的回调函数，入参分别为客户端IP和对应的指令文本
 */
/*class SocketServer(
    val port: Int = 9292,
    private val onClientConnected: (String) -> Unit,
    private val onClientDisconnected: (String) -> Unit,
    private val onCommandReceived: (String, String) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeClients = ConcurrentHashMap<String, Socket>()

    *//**
     * 将指令或视频切片数据广播发送给所有当前处于活跃连接状态的控制端客户端
     * @param message 需要广播分发的协议字符串内容或 Base64 视频流数据等
     *//*
    fun broadcastMessage(message: String) {
        scope.launch {
            activeClients.values.forEach { socket ->
                try {
                    withContext(Dispatchers.IO) {
                        val writer = PrintWriter(BufferedWriter(OutputStreamWriter(socket.getOutputStream())), true)
                        writer.println(message)
                    }
                } catch (e: Exception) {
                    Log.e("SocketServer", "单路客户端消息广播失败: ${e.message}")
                }
            }
        }
    }

    *//**
     * 启动 TCP 服务端，开始常驻进行监听与接受客户端连接接入
     *//*
    fun start() {
        if (isRunning) return
        isRunning = true
        scope.launch {
            try {
                serverSocket = ServerSocket(port).apply {
                    reuseAddress = true
                }
                Log.d("SocketServer", "服务端成功在端口 $port 启动")
                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    val clientIp = socket.inetAddress.hostAddress ?: "未知IP"
                    activeClients[clientIp] = socket
                    launch(Dispatchers.Main) {
                        onClientConnected(clientIp)
                    }
                    
                    launch {
                        handleClient(socket, clientIp)
                    }
                }
            } catch (e: Exception) {
                Log.e("SocketServer", "服务端套接字抛出异常: ${e.message}")
            }
        }
    }

    *//**
     * 针对单路连接接入的客户端建立指令监听和拉取解析轮询
     * @param socket 客户端会话 Socket
     * @param clientIp 客户端的 IP 地址
     *//*
    private suspend fun handleClient(socket: Socket, clientIp: String) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            while (isRunning) {
                val line = withContext(Dispatchers.IO) {
                    reader.readLine()
                } ?: break // 客户端主动关闭了连接
                
                withContext(Dispatchers.Main) {
                    onCommandReceived(clientIp, line)
                }
            }
        } catch (e: Exception) {
            Log.e("SocketServer", "读取客户端 $clientIp 数据时发生异常: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {}
            activeClients.remove(clientIp)
            withContext(Dispatchers.Main) {
                onClientDisconnected(clientIp)
            }
        }
    }

    *//**
     * 关闭并停止 TCP 服务端，彻底释放绑定的系统端口及已连接的客户端链路
     *//*
    fun stop() {
        isRunning = false
        scope.launch {
            try {
                serverSocket?.close()
            } catch (e: Exception) {}
            serverSocket = null
            activeClients.values.forEach {
                try {
                    it.close()
                } catch (e: Exception) {}
            }
            activeClients.clear()
            scope.coroutineContext.cancelChildren()
        }
    }
}*/

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

/**
 * TCP 通信客户端 (SocketClient)
 * 托管在控制端设备上运行，负责连接远端被控设备(服务端)，接收服务端传回的高帧率实时屏幕流数据
 * 并支持向服务端上报本地发起的交互手势指令以完成反向操纵。
 *
 * @property serverIp 欲连接的远端服务端 IP 地址
 * @property port 连接的目标 TCP 端口，默认 9292
 * @property onStateChanged 客户端物理连接状态流转时的响应回调通知
 * @property onMessageReceived 当收到服务端推下来的协议段、分辨率参数或 H264 视频切片时的即时解析回调
 */
/*class SocketClient(
    val serverIp: String,
    val port: Int = 9292,
    private val onStateChanged: (ConnectionState) -> Unit,
    private val onMessageReceived: (ByteArray) -> Unit
) {
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    *//**
     * 发起异步非阻塞的网络连接请求
     *//*
    fun connect() {
        if (isRunning) return
        isRunning = true
        onStateChanged(ConnectionState.Connecting)
        
        scope.launch {
            try {
                val targetSocket = Socket()
                targetSocket.connect(InetSocketAddress(serverIp, port), 3000)
                socket = targetSocket
                writer = PrintWriter(BufferedWriter(OutputStreamWriter(targetSocket.getOutputStream())), true)
                
                withContext(Dispatchers.Main) {
                    onStateChanged(ConnectionState.Connected)
                }
                
                val reader = BufferedReader(InputStreamReader(targetSocket.getInputStream()))
                while (isRunning) {
                    val line = withContext(Dispatchers.IO) {
                        reader.readLine()
                    } ?: break
                    withContext(Dispatchers.Main) {
                        onMessageReceived(line)
                    }
                }
            } catch (e: Exception) {
                Log.e("SocketClient", "连接目标主机 $serverIp 异常阻断: ${e.message}")
                withContext(Dispatchers.Main) {
                    onStateChanged(ConnectionState.Disconnected)
                }
            } finally {
                isRunning = false
                disconnectInternal()
            }
        }
    }

    *//**
     * 向已成功配对的服务端报送本地转换后的手势模拟原始指令
     * @param command 发送的目标指令协议串 (例如 "TAP:0.5,0.4" 或 "SWIPE:0.1,0.2;0.5,0.6")
     * @return 如果发送信道通畅且发送无错，返回 true，未连接或发送失败返回 false
     *//*
    fun sendCommand(command: String): Boolean {
        val w = writer
        if (w != null && socket?.isConnected == true) {
            scope.launch {
                try {
                    w.println(command)
                    Log.d("SocketClient", "发送指令成功: $command")
                } catch (e: Exception) {
                    Log.e("SocketClient", "指令发送失败异常: ${e.message}")
                }
            }
            return true
        }
        return false
    }

    *//**
     * 显式主动断开与服务端的 socket 直连通路
     *//*
    fun disconnect() {
        isRunning = false
        scope.launch {
            disconnectInternal()
        }
    }

    *//**
     * 关闭本地 Socket 连接及其关联数据写入器的具体底层执行程序
     *//*
    private suspend fun disconnectInternal() {
        try {
            socket?.close()
        } catch (e: Exception) {}
        socket = null
        writer = null
        withContext(Dispatchers.Main) {
            onStateChanged(ConnectionState.Disconnected)
        }
    }
}*/

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
                    delay(2000) // 每两秒向全网投递一次自身节点标识
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
