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

enum class ConnectionState {
    Disconnected,
    Connecting,
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
    private val activeClients = ConcurrentHashMap<String, Socket>()

    fun broadcastMessage(message: String) {
        scope.launch {
            activeClients.values.forEach { socket ->
                try {
                    withContext(Dispatchers.IO) {
                        val writer = PrintWriter(BufferedWriter(OutputStreamWriter(socket.getOutputStream())), true)
                        writer.println(message)
                    }
                } catch (e: Exception) {
                    Log.e("SocketServer", "Error sending broadcast: ${e.message}")
                }
            }
        }
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        scope.launch {
            try {
                serverSocket = ServerSocket(port).apply {
                    reuseAddress = true
                }
                Log.d("SocketServer", "Server started on port $port")
                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    val clientIp = socket.inetAddress.hostAddress ?: "Unknown"
                    activeClients[clientIp] = socket
                    launch(Dispatchers.Main) {
                        onClientConnected(clientIp)
                    }
                    
                    launch {
                        handleClient(socket, clientIp)
                    }
                }
            } catch (e: Exception) {
                Log.e("SocketServer", "Server socket error: ${e.message}")
            }
        }
    }

    private suspend fun handleClient(socket: Socket, clientIp: String) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            while (isRunning) {
                val line = withContext(Dispatchers.IO) {
                    reader.readLine()
                } ?: break // Connection closed by client
                
                withContext(Dispatchers.Main) {
                    onCommandReceived(clientIp, line)
                }
            }
        } catch (e: Exception) {
            Log.e("SocketServer", "Error reading client $clientIp: ${e.message}")
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
}

class SocketClient(
    val serverIp: String,
    val port: Int = 9292,
    private val onStateChanged: (ConnectionState) -> Unit,
    private val onMessageReceived: (String) -> Unit
) {
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
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
                Log.e("SocketClient", "Connection error to $serverIp: ${e.message}")
                withContext(Dispatchers.Main) {
                    onStateChanged(ConnectionState.Disconnected)
                }
            } finally {
                isRunning = false
                disconnectInternal()
            }
        }
    }

    fun sendCommand(command: String): Boolean {
        val w = writer
        if (w != null && socket?.isConnected == true) {
            scope.launch {
                try {
                    w.println(command)
                    Log.d("SocketClient", "Sent command: $command")
                } catch (e: Exception) {
                    Log.e("SocketClient", "Error sending: ${e.message}")
                }
            }
            return true
        }
        return false
    }

    fun disconnect() {
        isRunning = false
        scope.launch {
            disconnectInternal()
        }
    }

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
}

class UdpBroadcaster(
    private val serverIp: String,
    private val port: Int = 9293
) {
    private var socket: DatagramSocket? = null
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
                    delay(2000) // Broadcast every 2 seconds
                }
            } catch (e: Exception) {
                Log.e("UdpBroadcaster", "Broadcast error: ${e.message}")
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            socket?.close()
        } catch (e: Exception) {}
        socket = null
        scope.coroutineContext.cancelChildren()
    }
}

class UdpListener(
    private val port: Int = 9293,
    private val onServerDiscovered: (String) -> Unit
) {
    private var socket: DatagramSocket? = null
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
                Log.e("UdpListener", "Discovery listener stopped: ${e.message}")
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            socket?.close()
        } catch (e: Exception) {}
        socket = null
        scope.coroutineContext.cancelChildren()
    }
}

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
        Log.e("NetworkUtils", "IP Retrieval failed: ${e.message}")
    }
    return "127.0.0.1"
}
