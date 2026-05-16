package com.github.autodiag2.elm327emu.sim

object CarBridge {

    @Volatile
    private var rpm: Float = 0f

    @JvmStatic
    fun setRpm(value: Float) {
        rpm = value
    }

    @JvmStatic
    fun getRpm(): Float {
        return rpm
    }
}