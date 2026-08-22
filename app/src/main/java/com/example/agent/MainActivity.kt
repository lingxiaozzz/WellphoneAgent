package com.example.agent

import android.Manifest
import android.app.Activity
import android.app.ActivityOptions
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.Settings
import android.view.Display
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var calendarStatusView: TextView
    private lateinit var displaySpinner: Spinner
    private lateinit var textInput: EditText
    private lateinit var calendarPromptInput: EditText
    private var displayIds: List<Int> = emptyList()
    private var pendingCalendarPrompt: String? = null
    private var lastCreatedEventId: Long? = null
    private var lastCreatedEventStartMillis: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE).let { preferences ->
            lastCreatedEventId = preferences.getLong(LAST_EVENT_ID, -1L)
                .takeIf { it >= 0L }
            lastCreatedEventStartMillis = preferences.getLong(LAST_EVENT_START, -1L)
                .takeIf { it >= 0L }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 80, 40, 40)
        }

        val title = TextView(this).apply {
            text = "Shadow Display Agent"
            textSize = 26f
        }

        statusView = TextView(this).apply {
            textSize = 16f
        }

        val calendarTitle = TextView(this).apply {
            text = "后台日历提醒"
            textSize = 20f
        }

        calendarStatusView = TextView(this).apply {
            text = "日历状态：等待创建"
            textSize = 16f
        }

        calendarPromptInput = EditText(this).apply {
            hint = "例如：明天下午3点提醒我提交作业，提前30分钟通知"
            setText("明天下午3点提醒我提交作业，提前30分钟通知")
            minLines = 2
        }

        val createCalendarButton = Button(this).apply {
            text = "创建日历提醒"
            setOnClickListener {
                createCalendarReminder(calendarPromptInput.text.toString())
            }
        }

        val viewCalendarButton = Button(this).apply {
            text = "打开刚创建的日历事件"
            setOnClickListener { openLastCreatedEvent() }
        }

        val accessibilityButton = Button(this).apply {
            text = "1. 打开无障碍设置"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val refreshButton = Button(this).apply {
            text = "2. 刷新虚拟屏列表"
            setOnClickListener { refreshDisplays() }
        }

        displaySpinner = Spinner(this)

        val launchSettingsButton = Button(this).apply {
            text = "3. 在虚拟屏启动设置"
            setOnClickListener {
                val displayId = selectedDisplayId()
                if (displayId == null) {
                    showResult("没有可用的非主屏 Display")
                } else {
                    val intent = Intent(Settings.ACTION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    }
                    val options = ActivityOptions.makeBasic().apply {
                        launchDisplayId = displayId
                    }
                    startActivity(intent, options.toBundle())
                    showResult("已请求在 Display $displayId 启动设置")
                }
            }
        }

        val startButton = Button(this).apply {
            text = "4. 开始虚拟屏滑动测试"
            setOnClickListener {
                showResult(
                    selectedDisplayId()?.let(AgentAccessibilityService::startGestureLoop)
                        ?: "没有可用的非主屏 Display",
                )
            }
        }

        val stopButton = Button(this).apply {
            text = "停止虚拟屏操作"
            setOnClickListener {
                showResult(AgentAccessibilityService.stopGestureLoop())
            }
        }

        textInput = EditText(this).apply {
            hint = "写入虚拟屏焦点控件的文字"
            setText("Agent text without IME")
        }

        val setTextButton = Button(this).apply {
            text = "向虚拟屏焦点控件写入文字"
            setOnClickListener {
                val displayId = selectedDisplayId()
                showResult(
                    if (displayId == null) {
                        "没有可用的非主屏 Display"
                    } else {
                        AgentAccessibilityService.setFocusedText(
                            displayId,
                            textInput.text.toString(),
                        )
                    },
                )
            }
        }

        layout.addView(title)
        layout.addView(statusView)
        layout.addView(calendarTitle)
        layout.addView(calendarStatusView)
        layout.addView(calendarPromptInput)
        layout.addView(createCalendarButton)
        layout.addView(viewCalendarButton)
        layout.addView(accessibilityButton)
        layout.addView(refreshButton)
        layout.addView(displaySpinner)
        layout.addView(launchSettingsButton)
        layout.addView(startButton)
        layout.addView(stopButton)
        layout.addView(textInput)
        layout.addView(setTextButton)

        val scrollView = ScrollView(this).apply {
            addView(layout)
        }
        setContentView(scrollView)
        refreshDisplays()
    }

    override fun onResume() {
        super.onResume()
        refreshDisplays()
    }

    private fun refreshDisplays() {
        val manager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = manager.displays
            .filter { it.displayId != Display.DEFAULT_DISPLAY }
            .sortedByDescending(Display::getDisplayId)

        displayIds = displays.map(Display::getDisplayId)
        val labels = if (displays.isEmpty()) {
            listOf("未发现虚拟屏；请先启动修改版 scrcpy")
        } else {
            displays.map { "Display ${it.displayId}: ${it.name}" }
        }
        displaySpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels,
        )

        statusView.text = buildString {
            append("Accessibility: ")
            append(if (AgentAccessibilityService.isConnected()) "已连接" else "未启用")
            append("\n非主屏数量: ")
            append(displays.size)
        }
    }

    private fun selectedDisplayId(): Int? {
        val position = displaySpinner.selectedItemPosition
        return displayIds.getOrNull(position)
    }

    private fun showResult(message: String) {
        statusView.text = message
    }

    private fun showCalendarResult(message: String) {
        calendarStatusView.text = message
    }

    private fun createCalendarReminder(prompt: String) {
        if (!hasCalendarPermissions()) {
            pendingCalendarPrompt = prompt
            requestPermissions(
                arrayOf(
                    Manifest.permission.READ_CALENDAR,
                    Manifest.permission.WRITE_CALENDAR,
                ),
                PERMISSION_REQUEST,
            )
            return
        }

        showCalendarResult("正在创建日历提醒，并等待 Google 同步成功…")
        Thread {
            val result = runCatching {
                val task = CalendarTaskParser.parse(prompt)
                CalendarRepository(this).createReminder(task)
            }
            runOnUiThread {
                result.fold(
                    onSuccess = { event ->
                        lastCreatedEventId = event.eventId
                        lastCreatedEventStartMillis = event.startMillis
                        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
                            .edit()
                            .putLong(LAST_EVENT_ID, event.eventId)
                            .putLong(LAST_EVENT_START, event.startMillis)
                            .apply()
                        showCalendarResult(
                            buildString {
                                append("✅ 日历创建并同步成功")
                                append("\n日历：${event.calendarName}")
                                append("\neventId：${event.eventId}")
                            },
                        )
                    },
                    onFailure = { error ->
                        showCalendarResult(
                            "创建失败：${error.message ?: error.javaClass.simpleName}",
                        )
                    },
                )
            }
        }.start()
    }

    private fun hasCalendarPermissions(): Boolean =
        checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST) {
            return
        }

        if (hasCalendarPermissions()) {
            val prompt = pendingCalendarPrompt
            pendingCalendarPrompt = null
            if (prompt != null) {
                createCalendarReminder(prompt)
            }
        } else {
            pendingCalendarPrompt = null
            showCalendarResult("需要日历读写权限才能创建提醒")
        }
    }

    private fun openLastCreatedEvent() {
        val startMillis = lastCreatedEventStartMillis
            ?: runCatching {
                CalendarTaskParser.parse(calendarPromptInput.text.toString()).startMillis
            }.getOrNull()
            ?: return showResult("没有可打开的日历时间")

        val eventId = lastCreatedEventId
        if (eventId != null) {
            val eventUri = ContentUris.withAppendedId(
                CalendarContract.Events.CONTENT_URI,
                eventId,
            )
            val eventIntent = Intent(Intent.ACTION_VIEW, eventUri).apply {
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
                putExtra(
                    CalendarContract.EXTRA_EVENT_END_TIME,
                    startMillis + 60 * 60 * 1000L,
                )
            }
            if (eventIntent.resolveActivity(packageManager) != null) {
                val opened = runCatching { startActivity(eventIntent) }.isSuccess
                if (opened) {
                    return
                }
            }
        }

        val timeUri = CalendarContract.CONTENT_URI
            .buildUpon()
            .appendPath("time")
            .appendPath(startMillis.toString())
            .build()
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).setData(timeUri))
        }.onFailure {
            showResult("无法打开日历：${it.message}")
        }
    }

    companion object {
        private const val PERMISSION_REQUEST = 2001
        private const val PREFERENCES_NAME = "calendar_agent"
        private const val LAST_EVENT_ID = "last_event_id"
        private const val LAST_EVENT_START = "last_event_start"
    }
}