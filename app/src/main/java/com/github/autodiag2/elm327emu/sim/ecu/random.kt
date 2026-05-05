package com.github.autodiag2.elm327emu.sim.ecu

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.constraintlayout.widget.ConstraintLayout
import com.github.autodiag2.elm327emu.EcuConfig
import com.github.autodiag2.elm327emu.EcuType
import com.github.autodiag2.elm327emu.MainActivity
import com.github.autodiag2.elm327emu.R
import com.github.autodiag2.elm327emu.libautodiag

class ECUConfigRandom(
    address: Int,
    name: String,
    activity: MainActivity
): EcuConfig(address, name, EcuType.RANDOM, ConstraintLayout(activity)) {

    init {
        LayoutInflater.from(activity).inflate(R.layout.sim_ecu_random, this.screen as ViewGroup, true)
        val seed: EditText = this.screen.findViewById(R.id.sim_ecu_random_seed_input)
        this.screen.findViewById<Button>(R.id.sim_ecu_random_validate).setOnClickListener {
            setByAddressWithContext(address.toByte(), seed.text.toString())
        }
        setByAddressWithContext(address.toByte())
    }

    private fun setByAddressWithContext(address: Byte, context: String = "0") {
        libautodiag.setResponseTypeContextByAddress(address.toByte(), "random", context)
    }

}
