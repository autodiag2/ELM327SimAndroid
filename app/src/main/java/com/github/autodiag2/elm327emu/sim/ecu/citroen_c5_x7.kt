package com.github.autodiag2.elm327emu.sim.ecu

import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import com.github.autodiag2.elm327emu.MainActivity
import com.github.autodiag2.elm327emu.R
import com.github.autodiag2.elm327emu.libautodiag

class EcuCitroenC5X7(
    address: Byte = Ecu.DEFAULT_ADDRESS.toByte(),
    name: String = EcuType.CitroenC5X7.toString(),
    activity: MainActivity
): Ecu(EcuType.CitroenC5X7, address, name, activity) {

    init {
        LayoutInflater.from(activity).inflate(R.layout.sim_ecu_citroen_c5_x7, this, true)
        val seed: EditText = findViewById(R.id.sim_ecu_citroen_c5_x7_seed_input)
        findViewById<Button>(R.id.sim_ecu_citroen_c5_x7_validate).setOnClickListener {
            setByAddressWithContext(seed.text.toString())
        }
        setByAddressWithContext(seed.text.toString())
    }

    private fun setByAddressWithContext(context: String) {
        libautodiag.setResponseTypeContextByAddress(address, "citroen_c5_x7", context)
    }

}
