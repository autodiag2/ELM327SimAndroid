package com.github.autodiag2.elm327emu.sim

import com.github.autodiag2.elm327emu.MainActivity

class GuiGodot(
    @Suppress("UNUSED_PARAMETER")
    private val activity: MainActivity
) {
    fun show() {
    }

    fun refreshPeriodically(
        @Suppress("UNUSED_PARAMETER")
        hz: Int = 10
    ) {
    }

    fun updateSignal(
        @Suppress("UNUSED_PARAMETER")
        signalPath: String,
        @Suppress("UNUSED_PARAMETER")
        value: Double
    ) {
    }

    fun onDestroy() {
    }

    fun isVisible(): Boolean = false
}