package com.github.autodiag2.elm327emu.sim.ecu

import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import com.github.autodiag2.elm327emu.MainActivity
import com.github.autodiag2.elm327emu.R
import com.github.autodiag2.elm327emu.libautodiag
import org.json.JSONObject

class EcuReplay(
    address: EcuAddress = DEFAULT_ADDRESS.toUByte(),
    name: String = EcuType.REPLAY.toString(),
    activity: MainActivity
): Ecu(EcuType.REPLAY, address, name, activity) {

    private val jsonInput: EditText

    init {
        LayoutInflater.from(activity).inflate(R.layout.sim_ecu_replay, this, true)
        jsonInput = this.findViewById(R.id.sim_ecu_replay_json_input)
        this.findViewById<Button>(R.id.sim_ecu_replay_validate).setOnClickListener {
            updateFromContent()
        }
        this.findViewById<Button>(R.id.sim_ecu_replay_copy).setOnClickListener {
            val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager

            val clip = android.content.ClipData.newPlainText(
                "ECU Replay JSON",
                jsonInput.text.toString()
            )

            clipboard.setPrimaryClip(clip)
        }
        this.findViewById<Button>(R.id.sim_ecu_replay_paste).setOnClickListener {
            val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager

            val clip = clipboard.primaryClip

            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(activity).toString()
                setJson(text)
                updateFromContent()
            }
        }
        this.findViewById<Button>(R.id.sim_ecu_replay_import_file).setOnClickListener {
            activity.launchJsonPicker { text ->
                setJson(text)
                updateFromContent()
            }
        }
        this.findViewById<Button>(R.id.sim_ecu_replay_format).setOnClickListener {
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
        libautodiag.setResponseTypeContextByAddress(address, "replay", context)
    }

}