package com.example.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AgentAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var gestureDisplayId = Display.INVALID_DISPLAY
    private var gestureLoopRunning = false
    private var swipeUp = true

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    // Interruptions only cancel current accessibility feedback. The agent loop
    // must survive when the user changes focus or switches apps on Display 0.
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        stopGestureLoopInternal()
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    private fun startGestureLoopInternal(displayId: Int): String {
        if (displayId == Display.DEFAULT_DISPLAY) {
            return "拒绝操作主屏 Display 0"
        }

        val displayManager = getSystemService(DisplayManager::class.java)
        if (displayManager.getDisplay(displayId) == null) {
            return "找不到 Display $displayId"
        }

        stopGestureLoopInternal()
        gestureDisplayId = displayId
        gestureLoopRunning = true
        swipeUp = true
        mainHandler.post(::dispatchNextSwipe)
        return "正在 Display $displayId 循环滑动"
    }

    private fun stopGestureLoopInternal() {
        gestureLoopRunning = false
        gestureDisplayId = Display.INVALID_DISPLAY
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun dispatchNextSwipe() {
        if (!gestureLoopRunning || gestureDisplayId == Display.INVALID_DISPLAY) {
            return
        }

        val display = getSystemService(DisplayManager::class.java)
            .getDisplay(gestureDisplayId)
        if (display == null) {
            stopGestureLoopInternal()
            return
        }

        val size = Point()
        display.getRealSize(size)
        val centerX = size.x / 2f
        val upperY = size.y * 0.3f
        val lowerY = size.y * 0.75f

        val path = Path().apply {
            if (swipeUp) {
                moveTo(centerX, lowerY)
                lineTo(centerX, upperY)
            } else {
                moveTo(centerX, upperY)
                lineTo(centerX, lowerY)
            }
        }

        val gesture = GestureDescription.Builder()
            .setDisplayId(gestureDisplayId)
            .addStroke(GestureDescription.StrokeDescription(path, 0, 350))
            .build()

        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    swipeUp = !swipeUp
                    scheduleNextSwipe()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    scheduleNextSwipe()
                }
            },
            mainHandler,
        )

        if (!accepted) {
            scheduleNextSwipe()
        }
    }

    private fun scheduleNextSwipe() {
        if (gestureLoopRunning) {
            mainHandler.postDelayed(::dispatchNextSwipe, 500)
        }
    }

    private fun setFocusedTextInternal(displayId: Int, text: String): String {
        if (displayId == Display.DEFAULT_DISPLAY) {
            return "拒绝向主屏 Display 0 写入文字"
        }

        val focusedNode = windows
            .firstOrNull { it.displayId == displayId }
            ?.root
            ?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return "Display $displayId 没有获得输入焦点的控件"

        if (!focusedNode.isEditable) {
            return "Display $displayId 的焦点控件不可编辑"
        }

        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text,
            )
        }
        return if (focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
            "已向 Display $displayId 写入文字"
        } else {
            "Display $displayId 拒绝 ACTION_SET_TEXT"
        }
    }

    companion object {
        @Volatile
        private var instance: AgentAccessibilityService? = null

        fun isConnected(): Boolean = instance != null

        fun startGestureLoop(displayId: Int): String =
            instance?.startGestureLoopInternal(displayId)
                ?: "请先启用 Agent Accessibility"

        fun stopGestureLoop(): String {
            val service = instance ?: return "Accessibility 服务未连接"
            service.stopGestureLoopInternal()
            return "已停止虚拟屏操作"
        }

        fun setFocusedText(displayId: Int, text: String): String =
            instance?.setFocusedTextInternal(displayId, text)
                ?: "请先启用 Agent Accessibility"
    }
}
