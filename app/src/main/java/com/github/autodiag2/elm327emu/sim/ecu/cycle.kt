package com.github.autodiag2.elm327emu.sim.ecu

import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import com.github.autodiag2.elm327emu.MainActivity
import com.github.autodiag2.elm327emu.R
import com.github.autodiag2.elm327emu.libautodiag

class EcuCycle(
    address: Byte = Ecu.DEFAULT_ADDRESS.toByte(),
    name: String = EcuType.CYCLE.toString(),
    activity: MainActivity
): Ecu(EcuType.CYCLE, address, name, activity) {

    init {
        LayoutInflater.from(activity).inflate(R.layout.sim_ecu_cycle, this, true)
        val gears: EditText = findViewById(R.id.sim_ecu_cycle_gears_input)
        findViewById<Button>(R.id.sim_ecu_cycle_gears_validate).setOnClickListener {
            setByAddressWithContext(gears.text.toString())
        }
        setByAddressWithContext(gears.text.toString())
    }

    private fun setByAddressWithContext(context: String) {
        libautodiag.setResponseTypeContextByAddress(address, "cycle", context)
    }

}