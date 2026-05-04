package com.github.autodiag2.elm327emu.sim.ecu

import android.view.View
import com.github.autodiag2.elm327emu.EcuConfig
import com.github.autodiag2.elm327emu.EcuType
import com.github.autodiag2.elm327emu.MainActivity
import com.github.autodiag2.elm327emu.libautodiag

class ECUConfigCycle(
    address: Int,
    name: String,
    activity: MainActivity
): EcuConfig(address, name, EcuType.CYCLE, View(activity)) {

    init {
        libautodiag.setResponseTypeContextByAddress(address.toByte(), "cycle", "10")
    }

}