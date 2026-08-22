package com.example.agent

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.provider.Settings
import android.view.Display
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var displaySpinner: Spinner
    private lateinit var textInput: EditText
    private var displayIds: List<Int> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
        layout.addView(accessibilityButton)
        layout.addView(refreshButton)
        layout.addView(displaySpinner)
        layout.addView(launchSettingsButton)
        layout.addView(startButton)
        layout.addView(stopButton)
        layout.addView(textInput)
        layout.addView(setTextButton)

        setContentView(layout)
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
}