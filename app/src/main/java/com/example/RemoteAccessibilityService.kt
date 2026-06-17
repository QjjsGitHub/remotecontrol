package com.example

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class RemoteAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "RemoteAccessibilityService"
        private var instance: RemoteAccessibilityService? = null

        /**
         * Simulates a single-point click/tap globally
         */
        fun performTap(x: Float, y: Float): Boolean {
            val inst = instance
            if (inst == null) {
                Log.w(TAG, "Cannot tap: RemoteAccessibilityService is not enabled/running")
                return false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val path = Path()
                path.moveTo(x, y)
                val stroke = GestureDescription.StrokeDescription(path, 0, 50)
                val builder = GestureDescription.Builder()
                builder.addStroke(stroke)
                return inst.dispatchGesture(builder.build(), null, null)
            }
            return false
        }

        /**
         * Simulates a drag/swipe gesture globally
         */
        fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300): Boolean {
            val inst = instance
            if (inst == null) {
                Log.w(TAG, "Cannot swipe: RemoteAccessibilityService is not enabled/running")
                return false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val path = Path()
                path.moveTo(startX, startY)
                path.lineTo(endX, endY)
                val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
                val builder = GestureDescription.Builder()
                builder.addStroke(stroke)
                return inst.dispatchGesture(builder.build(), null, null)
            }
            return false
        }

        /**
         * Checks if the Accessibility Service is active and bound
         */
        fun isServiceRunning(): Boolean {
            return instance != null
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "RemoteAccessibilityService Active and Bound successfully")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        Log.i(TAG, "RemoteAccessibilityService Destroyed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No event listening required, purely gesture injector
    }

    override fun onInterrupt() {
        // No interrupt handling needed
    }
}
