package com.github.autodiag2.elm327emu.sim

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.URLSpan
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.core.view.setPadding
import com.github.autodiag2.elm327emu.MainActivity
import com.github.autodiag2.elm327emu.R

class GuiGodot(
    @Suppress("UNUSED_PARAMETER")
    private val activity: MainActivity
) {

    fun show() {
        val releasesUrl =
            "https://github.com/autodiag2/ELM327SimAndroid/releases/latest"

        val message = TextView(activity).apply {
            setPadding(
                24,
                8,
                24,
                8
            )

            val text = SpannableStringBuilder()

            text.append(activity.getString(R.string.sim_ecu_gui_godot_lite_unsuported_dialog_desc))

            val releasesStart = text.length
            text.append(releasesUrl)
            val releasesEnd = text.length

            text.setSpan(
                URLSpan(releasesUrl),
                releasesStart,
                releasesEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            this.text = text
            movementMethod = LinkMovementMethod.getInstance()
            setTextIsSelectable(true)
        }

        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.sim_ecu_gui_godot_lite_unsuported_dialog_title))
            .setView(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    fun refreshPeriodically(
        @Suppress("UNUSED_PARAMETER")
        hz: Int = 10
    ) {
    }

    fun updateSignal(
        @Suppress("UNUSED_PARAMETER")
        signalPath: String,
        @Suppress("UNUSED_PARAMETER")
        value: Double
    ) {
    }

    fun onDestroy() {
    }

    fun isVisible(): Boolean = false
}