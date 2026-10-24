package com.github.autodiag2.elm327emu.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.AttributeSet
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.github.autodiag2.elm327emu.R

class CopyableIpPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {

    fun getString(resId: Int, vararg formatArgs: Any?): String {
        return context.getString(resId, *formatArgs.map { it ?: "" }.toTypedArray())
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        holder.itemView.setOnLongClickListener {
            val ip = summary?.toString()

            if (!ip.isNullOrBlank()) {
                val clipboard =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

                clipboard.setPrimaryClip(
                    ClipData.newPlainText(getString(R.string.settings_ip_title), ip)
                )

                Toast.makeText(
                    context,
                    getString(R.string.settings_ip_copied_clipboard),
                    Toast.LENGTH_SHORT
                ).show()

                true
            } else {
                false
            }
        }
    }
}