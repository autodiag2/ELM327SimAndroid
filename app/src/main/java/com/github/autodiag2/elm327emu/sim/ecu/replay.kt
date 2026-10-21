package com.github.autodiag2.elm327emu.sim.ecu

import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import com.github.autodiag2.elm327emu.MainActivity
import com.github.autodiag2.elm327emu.R
import com.github.autodiag2.elm327emu.libautodiag
import org.json.JSONObject
import android.content.Context
import com.github.autodiag2.elm327emu.EmulatorForegroundService

class EcuReplayView(context: Context) : EcuView(context) {
    public val jsonInput: EditText
    public lateinit var onValidate: (() -> Unit)
    public lateinit var onPaste: ((text: String) -> Unit)
    init {
        LayoutInflater.from(context).inflate(R.layout.sim_ecu_replay, this, true)
        jsonInput = this.findViewById(R.id.sim_ecu_replay_json_input)
        findViewById<Button>(R.id.sim_ecu_replay_validate).setOnClickListener {
            onValidate.invoke()
        }
        findViewById<Button>(R.id.sim_ecu_replay_copy).setOnClickListener {
            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager

            val clip = android.content.ClipData.newPlainText(
                "ECU Replay JSON",
                jsonInput.text.toString()
            )

            clipboard.setPrimaryClip(clip)
        }
        findViewById<Button>(R.id.sim_ecu_replay_paste).setOnClickListener {
            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager

            val clip = clipboard.primaryClip

            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(context).toString()
                onPaste.invoke(text)
            }
        }
        findViewById<Button>(R.id.sim_ecu_replay_import_file).setOnClickListener {
            val activity = context as MainActivity
            activity.launchJsonPicker { text ->
                onPaste.invoke(text)
            }
        }
        findViewById<Button>(R.id.sim_ecu_replay_format).setOnClickListener {
            onPaste.invoke(jsonInput.text.toString())
        }
    }
}

class EcuReplay(
    address: EcuAddress = DEFAULT_ADDRESS,
    name: String? = null,
    service: EmulatorForegroundService
): Ecu(EcuType.replay, service, address, name ?: service.getString(EcuType.replay.label_id)) {


    init {
        libautodiag.registerOnSignalReceived(address, signalHandler)
    }

    fun onValidate() {
        updateFromContent()
    }

    fun onPaste(text: String) {
        setJson(text)
        updateFromContent()
    }

        
    override fun attachView(view: EcuView?) {
        assert(view is EcuReplayView)
        val view = view as EcuReplayView
        this.view = view
        view.onValidate = ::onValidate
        view.onPaste = ::onPaste
        setByAddressWithContext(view.jsonInput.text.toString())
    }

    private fun setJson(json: String) {
        val pretty = JSONObject(json).toString(2)
        val view = this.view as EcuReplayView?
        if ( view == null ) {
            return
        }
        val activity = view.context as MainActivity
        activity.runOnUiThread {
            view.jsonInput.setText(pretty)
        }
    }

    private fun updateFromContent() {
        val view = this.view as EcuReplayView?
        if ( view == null ) {
            return
        }
        setByAddressWithContext(view.jsonInput.text.toString())
    }

    private fun setByAddressWithContext(context: String) {
        libautodiag.setResponseTypeContextByAddress(address, "replay", context)
    }

}