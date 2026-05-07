package com.github.autodiag2.elm327emu.sim.ecu

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import com.github.autodiag2.elm327emu.EcuConfig
import com.github.autodiag2.elm327emu.EcuType
import com.github.autodiag2.elm327emu.MainActivity
import com.github.autodiag2.elm327emu.R
import com.github.autodiag2.elm327emu.libautodiag

class EcuCycle(
    address: Byte,
    name: String,
    activity: MainActivity
): EcuConfig(address, name, EcuType.CYCLE, LinearLayout(activity)) {

    init {
        LayoutInflater.from(activity).inflate(R.layout.sim_ecu_cycle, this.screen as ViewGroup, true)
        val gears: EditText = this.screen.findViewById(R.id.sim_ecu_cycle_gears_input)
        this.screen.findViewById<Button>(R.id.sim_ecu_cycle_gears_validate).setOnClickListener {
            setByAddressWithContext(gears.text.toString())
        }
        setByAddressWithContext(gears.text.toString())
    }

    private fun setByAddressWithContext(context: String) {
        libautodiag.setResponseTypeContextByAddress(address.toByte(), "cycle", context)
    }

}