package com.example.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class AgentAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val automationHandler = Handler(Looper.getMainLooper())
    private var gestureDisplayId = Display.INVALID_DISPLAY
    private var gestureLoopRunning = false
    private var swipeUp = true
    private var calendarAutomationDisplayId = Display.INVALID_DISPLAY
    private var calendarAutomationTitle = ""
    private var calendarAutomationStartMillis = 0L
    private var calendarAutomationEndMillis = 0L
    private var calendarAutomationStartedAt = 0L
    private var calendarTitleEntered = false
    private var calendarSaveClicked = false
    private var calendarWindowsLogged = false
    private var calendarDatePickerOpened = false
    private var calendarSettingEndDate = false
    private var calendarDateSelected = false
    private var calendarStartDateSet = false
    private var calendarEndDateSet = false
    private var calendarTimePickerOpened = false
    private var calendarSettingEndTime = false
    private var calendarTimeTextMode = false
    private var calendarTimeHourSet = false
    private var calendarTimeMinuteFocused = false
    private var calendarTimeMinuteSet = false
    private var calendarTimePeriodSet = false
    private var calendarStartTimeSet = false
    private var calendarEndTimeSet = false
    private var calendarAutomationProgress = ""
    private var calendarAutomationCallback: ((String) -> Unit)? = null

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    // Interruptions only cancel current accessibility feedback. The agent loop
    // must survive when the user changes focus or switches apps on Display 0.
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        stopGestureLoopInternal()
        finishCalendarAutomation("无障碍服务已停止")
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

    private fun startCalendarAutomationInternal(
        displayId: Int,
        task: CalendarTask,
        callback: (String) -> Unit,
    ): String {
        if (displayId == Display.DEFAULT_DISPLAY) {
            return "拒绝在主屏 Display 0 执行日历 UI 自动化"
        }
        if (getSystemService(DisplayManager::class.java).getDisplay(displayId) == null) {
            return "找不到 Display $displayId"
        }
        if (task.title.isBlank()) {
            return "日历标题不能为空"
        }

        cancelCalendarAutomation()
        calendarAutomationDisplayId = displayId
        calendarAutomationTitle = task.title
        calendarAutomationStartMillis = task.startMillis
        calendarAutomationEndMillis = task.endMillis
        calendarAutomationStartedAt = SystemClock.elapsedRealtime()
        calendarTitleEntered = false
        calendarSaveClicked = false
        calendarWindowsLogged = false
        calendarDatePickerOpened = false
        calendarSettingEndDate = false
        calendarDateSelected = false
        calendarStartDateSet = false
        calendarEndDateSet = false
        calendarTimePickerOpened = false
        calendarSettingEndTime = false
        calendarTimeTextMode = false
        calendarTimeHourSet = false
        calendarTimeMinuteFocused = false
        calendarTimeMinuteSet = false
        calendarTimePeriodSet = false
        calendarStartTimeSet = false
        calendarEndTimeSet = false
        calendarAutomationProgress = ""
        calendarAutomationCallback = callback
        reportCalendarAutomationProgress("等待 Google Calendar 界面加载")
        automationHandler.post(::runCalendarAutomationStep)
        return "正在 Display $displayId 通过 Google Calendar UI 创建事件"
    }

    private fun runCalendarAutomationStep() {
        if (calendarAutomationDisplayId == Display.INVALID_DISPLAY) {
            return
        }
        if (SystemClock.elapsedRealtime() - calendarAutomationStartedAt >
            CALENDAR_AUTOMATION_TIMEOUT_MILLIS
        ) {
            finishCalendarAutomation("UI 自动化超时，请确认 Google Calendar 已登录且界面可操作")
            return
        }

        val root = windowsOnAllDisplays
            .get(calendarAutomationDisplayId)
            .orEmpty()
            .firstOrNull {
                it.root?.packageName?.toString() == GOOGLE_CALENDAR_PACKAGE
            }
            ?.root

        if (root == null) {
            if (!calendarWindowsLogged) {
                calendarWindowsLogged = true
                Log.d(
                    LOG_TAG,
                    "目标 Display=$calendarAutomationDisplayId; 可见窗口=" +
                        buildList {
                            val windowsByDisplay = windowsOnAllDisplays
                            for (index in 0 until windowsByDisplay.size()) {
                                windowsByDisplay.valueAt(index).forEach {
                                    add("${it.displayId}:${it.root?.packageName ?: "无根节点"}")
                                }
                            }
                        }.joinToString(),
                )
            }
            scheduleCalendarAutomationStep()
            return
        }

        if (calendarAutomationProgress == "等待 Google Calendar 界面加载") {
            reportCalendarAutomationProgress("已连接虚拟屏 Calendar，正在查找新建事件入口")
        }
        val nodes = collectNodes(root)
        val titleField = nodes.firstOrNull(::isCalendarTitleField)
        val saveNode = nodes.firstOrNull {
            matchesAny(it.text, SAVE_LABELS) || matchesAny(it.contentDescription, SAVE_LABELS)
        }

        if (calendarDatePickerOpened) {
            if (!calendarDateSelected) {
                val targetMillis = if (calendarSettingEndDate) {
                    calendarAutomationEndMillis
                } else {
                    calendarAutomationStartMillis
                }
                val targetDate = Instant.ofEpochMilli(targetMillis)
                    .atZone(ZoneId.systemDefault())
                val englishDate = targetDate.format(
                    DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.ENGLISH),
                )
                val chineseDate = "${targetDate.monthValue}月${targetDate.dayOfMonth}日"
                val dateNode = nodes.firstOrNull {
                    val description = it.contentDescription?.toString().orEmpty()
                    it.isClickable &&
                        (description.contains(englishDate, ignoreCase = true) ||
                            description.contains(chineseDate))
                }
                if (dateNode != null && clickNode(dateNode)) {
                    calendarDateSelected = true
                    reportCalendarAutomationProgress(
                        if (calendarSettingEndDate) {
                            "已选择结束日期，正在确认"
                        } else {
                            "已选择开始日期，正在确认"
                        },
                    )
                }
                scheduleCalendarAutomationStep()
                return
            }
            val confirmNode = nodes.firstOrNull {
                it.viewIdResourceName?.endsWith(":id/confirm_button") == true
            }
            if (confirmNode != null && clickNode(confirmNode)) {
                calendarDatePickerOpened = false
                calendarDateSelected = false
                if (calendarSettingEndDate) {
                    calendarEndDateSet = true
                    reportCalendarAutomationProgress("已设置结束日期，正在设置结束时间")
                } else {
                    calendarStartDateSet = true
                    reportCalendarAutomationProgress("已设置开始日期，正在设置开始时间")
                }
            }
            scheduleCalendarAutomationStep()
            return
        }

        if (calendarTimePickerOpened) {
            if (!calendarTimeTextMode) {
                val modeNode = nodes.firstOrNull {
                    it.viewIdResourceName?.endsWith(":id/material_timepicker_mode_button") ==
                        true
                }
                if (modeNode != null && clickNode(modeNode)) {
                    calendarTimeTextMode = true
                    automationHandler.postDelayed(
                        ::runCalendarAutomationStep,
                        UI_ACTION_DELAY_MILLIS,
                    )
                    return
                }
                scheduleCalendarAutomationStep()
                return
            }

            val targetMillis = if (calendarSettingEndTime) {
                calendarAutomationEndMillis
            } else {
                calendarAutomationStartMillis
            }
            val targetTime = Instant.ofEpochMilli(targetMillis)
                .atZone(ZoneId.systemDefault())
            val hour12 = when (val hour = targetTime.hour % 12) {
                0 -> 12
                else -> hour
            }

            if (!calendarTimeHourSet) {
                val hourInput = nodes.firstOrNull(AccessibilityNodeInfo::isEditable)
                if (hourInput != null &&
                    setNodeText(hourInput, hour12.toString().padStart(2, '0'))
                ) {
                    calendarTimeHourSet = true
                }
                scheduleCalendarAutomationStep()
                return
            }
            if (!calendarTimeMinuteFocused) {
                val minuteDisplay = nodes.firstOrNull {
                    it.isClickable &&
                        it.contentDescription?.toString()?.matches(
                            Regex("""\d+\s+minutes?""", RegexOption.IGNORE_CASE),
                        ) == true
                }
                if (minuteDisplay != null && clickNode(minuteDisplay)) {
                    calendarTimeMinuteFocused = true
                }
                scheduleCalendarAutomationStep()
                return
            }
            if (!calendarTimeMinuteSet) {
                val minuteInput = nodes.firstOrNull(AccessibilityNodeInfo::isEditable)
                if (minuteInput != null &&
                    setNodeText(minuteInput, targetTime.minute.toString().padStart(2, '0'))
                ) {
                    calendarTimeMinuteSet = true
                }
                scheduleCalendarAutomationStep()
                return
            }
            if (!calendarTimePeriodSet) {
                val targetPeriod = if (targetTime.hour >= 12) "PM" else "AM"
                val periodNode = nodes.firstOrNull {
                    it.text?.toString()?.equals(targetPeriod, ignoreCase = true) == true
                }
                if (periodNode != null && clickNode(periodNode)) {
                    calendarTimePeriodSet = true
                }
                scheduleCalendarAutomationStep()
                return
            }

            val okNode = nodes.firstOrNull {
                it.viewIdResourceName?.endsWith(":id/material_timepicker_ok_button") == true
            }
            if (okNode != null && clickNode(okNode)) {
                calendarTimePickerOpened = false
                resetCalendarTimePickerState()
                if (calendarSettingEndTime) {
                    calendarEndTimeSet = true
                    reportCalendarAutomationProgress("已设置结束时间，正在保存事件")
                } else {
                    calendarStartTimeSet = true
                    reportCalendarAutomationProgress("已设置开始时间，正在设置结束日期")
                }
            }
            scheduleCalendarAutomationStep()
            return
        }

        if (calendarSaveClicked) {
            if (titleField == null && saveNode == null) {
                finishCalendarAutomation("✅ 已通过虚拟屏 UI 创建另一个日历事件")
                return
            }
            scheduleCalendarAutomationStep()
            return
        }

        if (titleField != null) {
            if (!calendarTitleEntered) {
                calendarTitleEntered = setNodeText(titleField, calendarAutomationTitle)
                if (calendarTitleEntered) {
                    reportCalendarAutomationProgress("已填写事件标题，正在设置日期")
                }
            }
            if (!calendarTitleEntered) {
                scheduleCalendarAutomationStep()
                return
            }

            val editorAction = when {
                !calendarStartDateSet -> Triple(
                    setOf("Start date:", "开始日期"),
                    "正在选择开始日期",
                    EditorPicker.START_DATE,
                )
                !calendarStartTimeSet -> Triple(
                    setOf("Start time:", "开始时间"),
                    "正在选择开始时间",
                    EditorPicker.START_TIME,
                )
                !calendarEndDateSet -> Triple(
                    setOf("End date:", "结束日期"),
                    "正在选择结束日期",
                    EditorPicker.END_DATE,
                )
                !calendarEndTimeSet -> Triple(
                    setOf("End time:", "结束时间"),
                    "正在选择结束时间",
                    EditorPicker.END_TIME,
                )
                else -> null
            }
            if (editorAction != null) {
                val targetNode = nodes.firstOrNull { node ->
                    val description = node.contentDescription?.toString().orEmpty()
                    node.isClickable && editorAction.first.any(description::startsWith)
                }
                if (targetNode != null && clickNode(targetNode)) {
                    when (editorAction.third) {
                        EditorPicker.START_DATE -> {
                            calendarSettingEndDate = false
                            calendarDatePickerOpened = true
                            calendarDateSelected = false
                        }
                        EditorPicker.END_DATE -> {
                            calendarSettingEndDate = true
                            calendarDatePickerOpened = true
                            calendarDateSelected = false
                        }
                        EditorPicker.START_TIME -> {
                            calendarSettingEndTime = false
                            calendarTimePickerOpened = true
                            resetCalendarTimePickerState()
                        }
                        EditorPicker.END_TIME -> {
                            calendarSettingEndTime = true
                            calendarTimePickerOpened = true
                            resetCalendarTimePickerState()
                        }
                    }
                    reportCalendarAutomationProgress(editorAction.second)
                    automationHandler.postDelayed(
                        ::runCalendarAutomationStep,
                        UI_ACTION_DELAY_MILLIS,
                    )
                    return
                }
                scheduleCalendarAutomationStep()
                return
            }

            if (calendarTitleEntered && saveNode != null && clickNode(saveNode)) {
                calendarSaveClicked = true
                reportCalendarAutomationProgress("已点击保存，正在确认创建结果")
            }
            scheduleCalendarAutomationStep()
            return
        }

        val eventNode = nodes.firstOrNull {
            matchesAny(it.text, EVENT_LABELS) || matchesAny(it.contentDescription, EVENT_LABELS)
        }
        if (eventNode != null && clickNode(eventNode)) {
            reportCalendarAutomationProgress("已选择“事件”，正在填写标题")
            automationHandler.postDelayed(::runCalendarAutomationStep, UI_ACTION_DELAY_MILLIS)
            return
        }

        val createNode = nodes.firstOrNull {
            containsAny(it.text, CREATE_LABELS) ||
                containsAny(it.contentDescription, CREATE_LABELS)
        }
        if (createNode != null) {
            if (clickNode(createNode)) {
                reportCalendarAutomationProgress("已点击新建按钮，正在选择“事件”")
            }
        }
        scheduleCalendarAutomationStep()
    }

    private fun scheduleCalendarAutomationStep() {
        if (calendarAutomationDisplayId != Display.INVALID_DISPLAY) {
            automationHandler.postDelayed(
                ::runCalendarAutomationStep,
                UI_POLL_INTERVAL_MILLIS,
            )
        }
    }

    private fun collectNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            result.add(node)
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(pending::addLast)
            }
        }
        return result
    }

    private fun isCalendarTitleField(node: AccessibilityNodeInfo): Boolean {
        if (!node.isEditable) {
            return false
        }
        val id = node.viewIdResourceName.orEmpty().lowercase()
        val label =
            "${node.text?.toString().orEmpty()} " +
                node.contentDescription?.toString().orEmpty()
        val normalizedLabel = label.lowercase()
        return "title" in id ||
            "title" in normalizedLabel ||
            "标题" in normalizedLabel ||
            "活动名称" in normalizedLabel
    }

    private fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text,
            )
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        repeat(MAX_CLICK_PARENT_DEPTH) {
            if (target?.isClickable == true) {
                return target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            target = target?.parent
        }
        return false
    }

    private fun matchesAny(value: CharSequence?, labels: Set<String>): Boolean {
        val normalized = value?.toString()?.trim()?.lowercase() ?: return false
        return labels.any { normalized == it }
    }

    private fun containsAny(value: CharSequence?, labels: Set<String>): Boolean {
        val normalized = value?.toString()?.trim()?.lowercase() ?: return false
        return labels.any { it in normalized }
    }

    private fun resetCalendarTimePickerState() {
        calendarTimeTextMode = false
        calendarTimeHourSet = false
        calendarTimeMinuteFocused = false
        calendarTimeMinuteSet = false
        calendarTimePeriodSet = false
    }

    private fun finishCalendarAutomation(message: String) {
        val callback = calendarAutomationCallback
        cancelCalendarAutomation()
        Log.d(LOG_TAG, message)
        callback?.invoke(message)
    }

    private fun reportCalendarAutomationProgress(message: String) {
        if (message == calendarAutomationProgress) {
            return
        }
        calendarAutomationProgress = message
        Log.d(LOG_TAG, message)
        calendarAutomationCallback?.invoke(message)
    }

    private fun cancelCalendarAutomation() {
        automationHandler.removeCallbacksAndMessages(null)
        calendarAutomationDisplayId = Display.INVALID_DISPLAY
        calendarAutomationTitle = ""
        calendarAutomationStartMillis = 0L
        calendarAutomationEndMillis = 0L
        calendarAutomationStartedAt = 0L
        calendarTitleEntered = false
        calendarSaveClicked = false
        calendarWindowsLogged = false
        calendarDatePickerOpened = false
        calendarSettingEndDate = false
        calendarDateSelected = false
        calendarStartDateSet = false
        calendarEndDateSet = false
        calendarTimePickerOpened = false
        calendarSettingEndTime = false
        resetCalendarTimePickerState()
        calendarStartTimeSet = false
        calendarEndTimeSet = false
        calendarAutomationProgress = ""
        calendarAutomationCallback = null
    }

    private enum class EditorPicker {
        START_DATE,
        START_TIME,
        END_DATE,
        END_TIME,
    }

    companion object {
        private const val GOOGLE_CALENDAR_PACKAGE = "com.google.android.calendar"
        private const val LOG_TAG = "AgentCalendarUi"
        private const val CALENDAR_AUTOMATION_TIMEOUT_MILLIS = 90_000L
        private const val UI_POLL_INTERVAL_MILLIS = 500L
        private const val UI_ACTION_DELAY_MILLIS = 800L
        private const val MAX_CLICK_PARENT_DEPTH = 5
        private val CREATE_LABELS = setOf("create", "new event", "创建", "新建", "新增")
        private val EVENT_LABELS = setOf("event", "活动", "事件", "日程")
        private val SAVE_LABELS = setOf("save", "保存")

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

        fun createCalendarWithUi(
            displayId: Int,
            task: CalendarTask,
            callback: (String) -> Unit,
        ): String =
            instance?.startCalendarAutomationInternal(displayId, task, callback)
                ?: "请先启用 Agent Accessibility"
    }
}
