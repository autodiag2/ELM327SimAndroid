package com.github.autodiag2.elm327emu.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.AttributeSet
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder

class CopyableIpPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        holder.itemView.setOnLongClickListener {
            val ip = summary?.toString()

            if (!ip.isNullOrBlank()) {
                val clipboard =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

                clipboard.setPrimaryClip(
                    ClipData.newPlainText("IP address", ip)
                )

                Toast.makeText(
                    context,
                    "IP address copied to clipboard",
                    Toast.LENGTH_SHORT
                ).show()

                true
            } else {
                false
            }
        }
    }
}