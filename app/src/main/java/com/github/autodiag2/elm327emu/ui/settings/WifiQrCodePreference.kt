package com.github.autodiag2.elm327emu.ui.settings

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
import com.github.autodiag2.elm327emu.R

class WifiQrCodePreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {

    private var qrBitmap: Bitmap? = null

    init {
        layoutResource = R.layout.preference_wifi_qrcode
        isSelectable = false
    }

    fun setQrCode(bitmap: Bitmap?) {
        qrBitmap = bitmap
        notifyChanged()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        
        val image = holder.findViewById(R.id.wifi_qrcode_image) as ImageView

        if (qrBitmap != null) {
            image.setImageBitmap(qrBitmap)
        } else {
            image.setImageResource(R.drawable.ic_wifi_qrcode_placeholder)
        }
    }
}