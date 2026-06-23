package com.example.domain.callback

import com.example.data.model.RemoteCommand
import com.example.data.model.ScreenSize
import kotlinx.coroutines.flow.StateFlow

/**
 * 远程控制回调接口
 * 用于Service与ViewModel/Repository之间的解耦通信
 */
interface RemoteControlCallback {

    /**
     * 当接收到远程控制命令时调用
     */
    fun onCommandReceived(command: RemoteCommand)

    /**
     * 当视频帧捕获完成时调用
     * @param data 视频数据
     * @param flags 帧标志
     * @param pts 时间戳
     */
    fun onVideoFrameCaptured(data: ByteArray, flags: Int, pts: Long)

    /**
     * 当屏幕尺寸发生变化时调用
     */
    fun onScreenSizeChanged(size: ScreenSize)

    /**
     * 发生错误时调用
     */
    fun onError(error: String)
}

/**
 * 服务状态接口
 */
interface ServiceStatusProvider {
    val isRunning: StateFlow<Boolean>
}

/**
 * 屏幕捕获服务回调接口
 */
interface ScreenCaptureCallback : RemoteControlCallback {
    /**
     * 当屏幕捕获服务状态变化时调用
     */
    fun onCaptureStateChanged(running: Boolean)
}

/**
 * 无障碍服务回调接口
 */
interface AccessibilityCallback {
    /**
     * 当无障碍服务状态变化时调用
     */
    fun onAccessibilityStateChanged(running: Boolean)
}

/**
 * 悬浮窗服务回调接口
 */
interface FloatingWindowCallback {
    /**
     * 当悬浮窗服务状态变化时调用
     */
    fun onFloatingWindowStateChanged(running: Boolean)

    /**
     * 当用户在悬浮窗上执行手势时调用
     */
    fun onGesturePerformed(command: String)
}

/**
 * 统一的服务回调接口
 */
interface UnifiedServiceCallback :
    ScreenCaptureCallback,
    AccessibilityCallback,
    FloatingWindowCallback