package com.github.autodiag2.elm327emu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.AttributeSet
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import android.graphics.Bitmap
import android.widget.ImageView
import android.widget.TextView

class WifiQrCodePreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {

    private var message = context.getString(
        R.string.settings_wifi_hotspot_not_started
    )

    private var qrBitmap: Bitmap? = null

    init {
        layoutResource = R.layout.preference_wifi_qrcode
    }

    fun setMessage(value: String) {
        message = value
        notifyChanged()
    }

    fun setQrCode(bitmap: Bitmap?) {
        qrBitmap = bitmap
        notifyChanged()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val text = holder.findViewById(R.id.wifi_qrcode_text) as TextView
        val image = holder.findViewById(R.id.wifi_qrcode_image) as ImageView

        text.text = message

        if (qrBitmap != null) {
            image.setImageBitmap(qrBitmap)
        } else {
            image.setImageResource(R.drawable.ic_wifi_qrcode_placeholder)
        }
    }
}