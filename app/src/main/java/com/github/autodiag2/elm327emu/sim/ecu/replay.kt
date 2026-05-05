package com.github.autodiag2.elm327emu.sim.ecu

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import com.github.autodiag2.elm327emu.EcuConfig
import com.github.autodiag2.elm327emu.EcuType
import com.github.autodiag2.elm327emu.MainActivity
import com.github.autodiag2.elm327emu.R
import com.github.autodiag2.elm327emu.libautodiag
import org.json.JSONObject

class ECUConfigReplay(
    address: Int,
    name: String,
    activity: MainActivity
): EcuConfig(address, name, EcuType.REPLAY, LinearLayout(activity)) {

    private lateinit var jsonInput: EditText

    private val filePicker = activity.registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val text = activity.contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                it.readText()
            }

            if (text != null) {
                setJson(text)
                updateFromContent()
            }
        }
    }

    init {
        LayoutInflater.from(activity).inflate(R.layout.sim_ecu_replay, this.screen as ViewGroup, true)
        jsonInput = this.screen.findViewById(R.id.sim_ecu_replay_json_input)
        this.screen.findViewById<Button>(R.id.sim_ecu_replay_validate).setOnClickListener {
            updateFromContent()
        }
        this.screen.findViewById<Button>(R.id.sim_ecu_replay_copy).setOnClickListener {
            val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager

            val clip = android.content.ClipData.newPlainText(
                "ECU Replay JSON",
                jsonInput.text.toString()
            )

            clipboard.setPrimaryClip(clip)
        }
        this.screen.findViewById<Button>(R.id.sim_ecu_replay_paste).setOnClickListener {
            val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager

            val clip = clipboard.primaryClip

            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(activity).toString()
                setJson(text)
                updateFromContent()
            }
        }
        this.screen.findViewById<Button>(R.id.sim_ecu_replay_import_file).setOnClickListener {
            filePicker.launch("application/json")
        }
        this.screen.findViewById<Button>(R.id.sim_ecu_replay_format).setOnClickListener {
            setJson(jsonInput.text.toString())
        }
        setByAddressWithContext(jsonInput.text.toString())
    }

    private fun setJson(json: String) {
        val pretty = JSONObject(json).toString(2)
        jsonInput.setText(pretty)
    }

    private fun updateFromContent() {
        setByAddressWithContext(jsonInput.text.toString())
    }

    private fun setByAddressWithContext(context: String) {
        libautodiag.setResponseTypeContextByAddress(id.toByte(), "replay", context)
    }

}