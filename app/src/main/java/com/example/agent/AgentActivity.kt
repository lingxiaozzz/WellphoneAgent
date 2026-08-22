package com.example.agent

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.TextView

class AgentActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val textView = TextView(this).apply {
            text = """
                🤖 AGENT DISPLAY

                Agent is running here.

                This is Display 5.
            """.trimIndent()

            textSize = 28f
            gravity = Gravity.CENTER
        }

        setContentView(textView)
    }
}