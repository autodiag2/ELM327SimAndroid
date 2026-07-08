package com.github.autodiag2.elm327emu.sim.ecu

import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import com.github.autodiag2.elm327emu.MainActivity
import com.github.autodiag2.elm327emu.R
import com.github.autodiag2.elm327emu.libautodiag
import org.json.JSONArray
import org.json.JSONObject

class EcuCycle(
    address: EcuAddress = DEFAULT_ADDRESS,
    name: String = EcuType.CYCLE.toString(),
    activity: MainActivity
): Ecu(EcuType.CYCLE, address, name, activity) {

    private val gears: EditText

    init {
        LayoutInflater.from(activity).inflate(R.layout.sim_ecu_cycle, this, true)
        gears = findViewById(R.id.sim_ecu_cycle_gears_input)
        findViewById<Button>(R.id.sim_ecu_cycle_gears_validate).setOnClickListener {
            libautodiag.simEcuLoadFromJson(address.toByte(), toJson().toString())
        }
        libautodiag.setResponseTypeByAddress(address, "cycle")
    }

    override fun toJsonInternal(): JSONObject {
        val content = JSONObject()
        content.put("gears", gears.getText().toString().toInt())
        return content
    }

    override fun fromJsonInternal(content: JSONObject) {
        gears.setText(content.getInt("gears"))
    }

}