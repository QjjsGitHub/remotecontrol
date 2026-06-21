package com.example

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.example.ui.LanRemoteViewModel
import com.example.ui.LogType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 远程触控辅助输入服务 (RemoteAccessibilityService)
 * 继承自系统的 AccessibilityService，用于在被控制端(服务端)模拟全局的触摸、滑动等手势操作。
 */
@SuppressLint("AccessibilityPolicy")
class RemoteAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "RemoteAccessibilityService"

        @Volatile
        private var instance: RemoteAccessibilityService? = null

        // 核心：使用 StateFlow 管理状态
        // 核心：使用 StateFlow 管理状态
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        // 用于处理连续手势的状态变量
        private var lastX = 0f
        private var lastY = 0f
        private var isDragging = false

        fun handleTouchDown(x: Float, y: Float) {
            lastX = x
            lastY = y
            isDragging = true
            // 发送一个极短的滑动或者点击来启动触摸
            performTap(x, y)
        }

        fun handleTouchMove(x: Float, y: Float) {
            if (!isDragging) return
            // 使用极短时间 (10ms) 的滑动来模拟实时拖动
            performSwipe(lastX, lastY, x, y, 10)
            lastX = x
            lastY = y
        }

        fun handleTouchUp() {
            isDragging = false
        }


        /**
         * 在屏幕的全局指定坐标处模拟单点轻触(点击)操作
         * @param x 目标点击位置的X轴物理像素坐标
         * @param y 目标点击位置的Y轴物理像素坐标
         * @return 如果手势分发请求成功发送则返回 true，否则返回 false
         */
        fun performTap(x: Float, y: Float): Boolean {
            val inst = instance
            if (inst == null) {
                Log.w(TAG, "无法执行点击：辅助服务未启用或未处于运行状态")
                return false
            }
            val path = Path()
            path.moveTo(x, y)
            // 模拟耗时 50 毫秒的单点轻刷，达成点击效果
            val stroke = GestureDescription.StrokeDescription(path, 0, 50)
            val builder = GestureDescription.Builder()
            builder.addStroke(stroke)
            return inst.dispatchGesture(builder.build(), null, null)
        }

        /**
         * 在屏幕上模拟全局的滑动/拖动轨迹手势
         * @param startX 起点位置的X轴物理像素坐标
         * @param startY 起点位置的Y轴物理像素坐标
         * @param endX 终点位置的X轴物理像素坐标
         * @param endY 终点位置的Y轴物理像素坐标
         * @param durationMs 滑动手势的持续时间，单位毫秒，默认 300 毫秒
         * @return 如果手势滑动请求成功发送则返回 true，否则返回 false
         */
        fun performSwipe(
            startX: Float,
            startY: Float,
            endX: Float,
            endY: Float,
            durationMs: Long = 300
        ): Boolean {
            val inst = instance
            if (inst == null) {
                Log.w(TAG, "无法执行滑动：辅助服务未启用或未处于运行状态")
                return false
            }
            val path = Path()
            path.moveTo(startX, startY)
            path.lineTo(endX, endY)
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
            val builder = GestureDescription.Builder()
            builder.addStroke(stroke)
            return inst.dispatchGesture(builder.build(), null, null)
        }

        /**
         * 执行系统级的返回键操作
         */
        fun performBack(): Boolean {
            return instance?.performGlobalAction(GLOBAL_ACTION_BACK) ?: false
        }

        /**
         * 执行系统级的回到桌面操作
         */
        fun performHome(): Boolean {
            return instance?.performGlobalAction(GLOBAL_ACTION_HOME) ?: false
        }

    }

    /**
     * 当辅助服务被系统成功开启并连接时回调，此时缓存当前服务实例以供全局快捷调用
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isRunning.value = true // 更新状态
        Log.i(TAG, "远程触控辅助服务成功激活并绑定")
    }

    /**
     * 服务销毁时回调，安全清空静态实例引用，避免内存泄漏
     */
    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
            _isRunning.value = false // 更新状态
        }
        Log.i(TAG, "远程触控辅助服务已销毁")
    }

    /**
     * 监听系统无障碍事件的回调方法
     * @param event 系统拦截并产生的分发事件
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                Log.d("onAccessibilityEvent", "TYPE_WINDOW_CONTENT_CHANGED")
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {

                // 只在窗口变化时记录，避免过于频繁
                val packageName = event.packageName?.toString() ?: "unknown"
                LanRemoteViewModel.instance?.addServerLog(
                    "窗口切换: $packageName",
                    LogType.INFO
                )
            }
            else -> {}
        }
    }

    /**
     * 服务被系统中断或意外挂起时的回调
     */
    override fun onInterrupt() {
        // 无需处理中断后的收尾逻辑
    }
}
