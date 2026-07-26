package com.github.autodiag2.elm327emu.sim.ecu

import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import com.github.autodiag2.elm327emu.MainActivity
import com.github.autodiag2.elm327emu.R
import com.github.autodiag2.elm327emu.libautodiag
import org.json.JSONArray
import org.json.JSONObject
import androidx.appcompat.widget.SwitchCompat

class EcuCycle(
    address: EcuAddress = DEFAULT_ADDRESS,
    name: String? = null,
    activity: MainActivity
): Ecu(EcuType.cycle, address, name ?: activity.getString(EcuType.cycle.label_id), activity) {

    private val gears: EditText
    private val use_signals: SwitchCompat

    init {
        LayoutInflater.from(activity).inflate(R.layout.sim_ecu_cycle, this, true)
        gears = findViewById(R.id.sim_ecu_cycle_gears_input)
        use_signals = findViewById(R.id.sim_ecu_cycle_use_signals)
        findViewById<Button>(R.id.sim_ecu_cycle_gears_validate).setOnClickListener {
            libautodiag.simEcuLoadFromJson(address.toByte(), toJson().toString())
        }
        libautodiag.setResponseTypeByAddress(address, EcuType.cycle.name)
        libautodiag.registerOnSignalReceived(address, signalHandler)
    }

    override fun toJsonInternal(): JSONObject {
        val content = JSONObject()
        content.put("gears", gears.getText().toString().toInt())
        content.put("use_signals", use_signals.isChecked)
        return content
    }

    override fun fromJsonInternal(content: JSONObject) {
        gears.setText(content.getInt("gears").toString())
        use_signals.isChecked = content.getBoolean("use_signals")
    }

}