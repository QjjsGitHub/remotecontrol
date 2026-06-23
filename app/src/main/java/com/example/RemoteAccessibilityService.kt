package com.example

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.example.data.model.LogType
import com.example.data.model.RemoteCommand
import com.example.data.repository.RemoteControlRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 远程触控辅助输入服务 (RemoteAccessibilityService)
 * 遵循 MVVM 模式。不再通过静态单例暴露，而是订阅 Repository 的命令流。
 */
@SuppressLint("AccessibilityPolicy")
@AndroidEntryPoint
class RemoteAccessibilityService : AccessibilityService() {

    @Inject
    lateinit var repository: RemoteControlRepository

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        private const val TAG = "RemoteAccessibilityService"
    }

    private var lastX = 0f
    private var lastY = 0f
    private var currentStroke: GestureDescription.StrokeDescription? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        repository.setAccessibilityRunning(true)

        serviceScope.launch {
            repository.clientCommands.collect { command ->
                executeCommand(command)
            }
        }

        Log.i(TAG, "远程触控辅助服务已激活")
    }

    private fun executeCommand(command: RemoteCommand) {
        val (screenWidth, screenHeight) = repository.getServerScreenSize()
        if (screenWidth <= 0 || screenHeight <= 0) return

        when (command) {
            is RemoteCommand.Tap -> performTap(command.x * screenWidth, command.y * screenHeight)
            is RemoteCommand.DoubleTap -> performDoubleTap(command.x * screenWidth, command.y * screenHeight)
            is RemoteCommand.LongPress -> performLongPress(command.x * screenWidth, command.y * screenHeight)
            is RemoteCommand.Down -> handleTouchDown(command.x * screenWidth, command.y * screenHeight)
            is RemoteCommand.Move -> handleTouchMove(command.x * screenWidth, command.y * screenHeight)
            is RemoteCommand.Up -> handleTouchUp()
            is RemoteCommand.Back -> performGlobalAction(GLOBAL_ACTION_BACK)
            is RemoteCommand.Home -> performGlobalAction(GLOBAL_ACTION_HOME)
            is RemoteCommand.Recent -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        }
    }

    private fun handleTouchDown(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 10, true)
            currentStroke = stroke
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        }
        lastX = x
        lastY = y
    }

    private fun handleTouchMove(x: Float, y: Float) {
        val prev = currentStroke
        if (prev != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val path = Path().apply {
                moveTo(lastX, lastY)
                lineTo(x, y)
            }
            val next = prev.continueStroke(path, 0, 10, true)
            currentStroke = next
            dispatchGesture(GestureDescription.Builder().addStroke(next).build(), null, null)
        }
        lastX = x
        lastY = y
    }

    private fun handleTouchUp() {
        val prev = currentStroke
        if (prev != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val path = Path().apply { moveTo(lastX, lastY) }
            val last = prev.continueStroke(path, 0, 10, false)
            dispatchGesture(GestureDescription.Builder().addStroke(last).build(), null, null)
        }
        currentStroke = null
    }

    private fun performTap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 30)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun performDoubleTap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke1 = GestureDescription.StrokeDescription(path, 0, 40)
        val stroke2 = GestureDescription.StrokeDescription(path, 100, 40)
        return dispatchGesture(
            GestureDescription.Builder().addStroke(stroke1).addStroke(stroke2).build(), null, null
        )
    }

    private fun performLongPress(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 800)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: "unknown"
            repository.addServerLog("窗口切换: $packageName", LogType.INFO)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        repository.setAccessibilityRunning(false)
        serviceScope.cancel()
    }
}
