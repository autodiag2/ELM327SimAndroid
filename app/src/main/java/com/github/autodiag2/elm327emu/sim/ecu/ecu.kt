package com.github.autodiag2.elm327emu.sim.ecu

import android.content.Context
import androidx.constraintlayout.widget.ConstraintLayout

abstract class Ecu(
    val address: Byte,
    var displayName: String,
    var type: EcuType,
    context: Context
) : ConstraintLayout(context)

enum class EcuType(val label: String) {
    GUI("GUI"),
    RANDOM("random"),
    CYCLE("cycle"),
    REPLAY("replay"),
    SCRIPT("Script");

    override fun toString() = label
}