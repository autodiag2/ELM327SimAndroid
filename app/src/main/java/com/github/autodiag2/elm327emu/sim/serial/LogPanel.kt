package com.github.autodiag2.elm327emu.sim.serial

import com.github.autodiag2.elm327emu.LogEntry
import com.github.autodiag2.elm327emu.LogEntryType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.github.autodiag2.elm327emu.R
import android.util.Log
import com.github.autodiag2.elm327emu.BuildConfig
import android.widget.Toast
import com.github.autodiag2.elm327emu.MainActivity
import com.github.autodiag2.elm327emu.com.Bridge
import com.github.autodiag2.elm327emu.sim.EmuInterface
import kotlinx.coroutines.currentCoroutineContext
import android.view.View
import android.widget.TextView
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.github.autodiag2.elm327emu.LogLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.widget.ScrollView

class LogPanel(custom: CustomController) {

    private var expanded = false
    
    private lateinit var panel: View
    private val container: View

    init {
        container = custom.view!!
    }

    fun setup() {
        panel = container.findViewById(R.id.custom_serial_log_panel)
        val logText = panel.findViewById<TextView>(R.id.custom_serial_log_text)
        val scroll = panel.findViewById<ScrollView>(R.id.custom_serial_log_scroll)
        val context = logText.context
        val toggle = panel.findViewById<TextView>(R.id.custom_serial_log_toggle)
        toggle.setOnClickListener {
            visibilityToggle()
        }
    }

    fun appendLog(text: String, level: LogLevel = LogLevel.DEBUG) {
        container.post {
            val logText = panel.findViewById<TextView>(R.id.custom_serial_log_text)
            val scroll = panel.findViewById<ScrollView>(R.id.custom_serial_log_scroll)
            val context = logText.context

            val primaryColor = android.util.TypedValue().let { value ->
                context.theme.resolveAttribute(
                    android.R.attr.textColorPrimary,
                    value,
                    true
                )
                if (value.resourceId != 0) {
                    ContextCompat.getColor(context, value.resourceId)
                } else {
                    value.data
                }
            }

            val color = when (level) {
                LogLevel.INFO -> ContextCompat.getColor(context, R.color.sol_blue)
                LogLevel.ERROR -> ContextCompat.getColor(context, R.color.sol_red)
                LogLevel.WARNING -> ContextCompat.getColor(context, R.color.sol_orange)
                LogLevel.NONE -> ContextCompat.getColor(context, R.color.sol_magenta)
                LogLevel.DEBUG -> androidx.core.graphics.ColorUtils
                    .setAlphaComponent(primaryColor, 128)
            }

            val timestamp = java.text.SimpleDateFormat(
                "HH:mm:ss.SSS",
                java.util.Locale.getDefault()
            ).format(java.util.Date())

            val line = android.text.SpannableString("$text\n") // $timestamp [$level]
            line.setSpan(
                android.text.style.ForegroundColorSpan(color),
                0,
                line.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            logText.append(line)

            scroll.post {
                scroll.fullScroll(View.FOCUS_DOWN)
            }

            panel.requestLayout()
            container.requestLayout()
            container.invalidate()
        }
    }

    public fun visibilityToggle() {
        val content = panel.findViewById<View>(R.id.custom_serial_log_content)
        val toggle = panel.findViewById<TextView>(R.id.custom_serial_log_toggle)
        val expandedWidth = (280 * panel.resources.displayMetrics.density).toInt()
        val collapsedWidth = toggle.width
    
        expanded = !expanded
    
        if (expanded) {
            content.visibility = View.VISIBLE
            panel.layoutParams = panel.layoutParams.apply {
                width = expandedWidth
            }
            toggle.text = "›"
        } else {
            content.visibility = View.GONE
            panel.layoutParams = panel.layoutParams.apply {
                width = collapsedWidth
            }
            toggle.text = "‹"
        }
        panel.requestLayout()
    }

}