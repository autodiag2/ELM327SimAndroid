package com.github.autodiag2.elm327emu.sim.ecu

import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import com.github.autodiag2.elm327emu.MainActivity
import com.github.autodiag2.elm327emu.R
import com.github.autodiag2.elm327emu.libautodiag

class EcuRandom(
    address: EcuAddress = DEFAULT_ADDRESS,
    name: String = EcuType.RANDOM.toString(),
    activity: MainActivity
): Ecu(EcuType.RANDOM, address, name, activity) {

    init {
        LayoutInflater.from(activity).inflate(R.layout.sim_ecu_random, this, true)
        val seed: EditText = findViewById(R.id.sim_ecu_random_seed_input)
        findViewById<Button>(R.id.sim_ecu_random_validate).setOnClickListener {
            setByAddressWithContext(seed.text.toString())
        }
        setByAddressWithContext(seed.text.toString())
    }

    private fun setByAddressWithContext(context: String) {
        libautodiag.setResponseTypeContextByAddress(address, "random", context)
    }

}
