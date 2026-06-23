package com.example.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * 远程连接的网络状态枚举
 */
sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data object Connected : ConnectionState()
}

/**
 * 服务器信息
 */
@Serializable
data class ServerInfo(
    val ip: String, val port: Int = 9292
)

/**
 * 客户端信息
 */
@Serializable
data class ClientInfo(
    val ip: String, val connectedAt: Long = System.currentTimeMillis()
)

/**
 * 视频帧数据
 */
data class VideoFrame(
    val data: ByteArray, val flags: Int, val pts: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as VideoFrame

        if (!data.contentEquals(other.data)) return false
        if (flags != other.flags) return false
        if (pts != other.pts) return false

        return true
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + flags
        result = 31 * result + pts.hashCode()
        return result
    }

    override fun toString(): String {
        return "VideoFrame(size=${data.size}, flags=$flags, pts=$pts)"
    }
}

/**
 * 屏幕尺寸信息
 */
@Serializable
data class ScreenSize(
    val width: Int, val height: Int, val rotation: Int // 0=竖屏, 1=横屏
) {
    val isLandscape: Boolean
        get() = rotation == 1

    val aspectRatio: Float
        get() = if (height > 0) width.toFloat() / height.toFloat() else 16f / 9f
}

/**
 * 服务器状态
 */
sealed class ServerState {
    data object Stopped : ServerState()
    data class Running(val ip: String, val port: Int) : ServerState()
}

/**
 * 日志类型
 */
enum class LogType {
    INFO, SUCCESS, WARNING, ERROR
}

/**
 * 日志条目
 */
@Serializable
data class LogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: String,
    val message: String,
    val type: LogType
)

/**
 * 远程控制命令
 */
sealed class RemoteCommand {
    data class Tap(val x: Float, val y: Float) : RemoteCommand()
    data class DoubleTap(val x: Float, val y: Float) : RemoteCommand()
    data class LongPress(val x: Float, val y: Float) : RemoteCommand()
    data class Down(val x: Float, val y: Float) : RemoteCommand()
    data class Move(val x: Float, val y: Float) : RemoteCommand()
    data object Up : RemoteCommand()
    data object Back : RemoteCommand()
    data object Home : RemoteCommand()
    data object Recent : RemoteCommand()

    fun toProtocolString(): String = when (this) {
        is Tap -> "TAP:$x,$y"
        is DoubleTap -> "DOUBLE_TAP:$x,$y"
        is LongPress -> "LONG_PRESS:$x,$y"
        is Down -> "DOWN:$x,$y"
        is Move -> "MOVE:$x,$y"
        is Up -> "UP"
        is Back -> "BACK"
        is Home -> "HOME"
        is Recent -> "RECENT"
    }

    companion object {
        fun fromProtocolString(str: String): RemoteCommand? = try {
            when {
                str.startsWith("TAP:") -> {
                    val parts = str.substringAfter("TAP:").split(",")
                    if (parts.size == 2) Tap(parts[0].toFloat(), parts[1].toFloat()) else null
                }

                str.startsWith("DOUBLE_TAP:") -> {
                    val parts = str.substringAfter("DOUBLE_TAP:").split(",")
                    if (parts.size == 2) DoubleTap(parts[0].toFloat(), parts[1].toFloat()) else null
                }

                str.startsWith("LONG_PRESS:") -> {
                    val parts = str.substringAfter("LONG_PRESS:").split(",")
                    if (parts.size == 2) LongPress(parts[0].toFloat(), parts[1].toFloat()) else null
                }

                str.startsWith("DOWN:") -> {
                    val parts = str.substringAfter("DOWN:").split(",")
                    if (parts.size == 2) Down(parts[0].toFloat(), parts[1].toFloat()) else null
                }

                str.startsWith("MOVE:") -> {
                    val parts = str.substringAfter("MOVE:").split(",")
                    if (parts.size == 2) Move(parts[0].toFloat(), parts[1].toFloat()) else null
                }

                str == "UP" -> Up
                str == "BACK" -> Back
                str == "HOME" -> Home
                str == "RECENT" -> Recent
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}