package com.github.autodiag2.elm327emu.sim

object CarBridge {

    @Volatile private var rpm: Float = 0f

    @JvmStatic
    fun setRpm(value: Float) {
        rpm = value
    }

    @JvmStatic
    fun getRpm(): Float {
        return rpm
    }

    @Volatile private var speed: Float = 0f

    @JvmStatic
    fun setSpeed(value: Float) {
        speed = value
    }

    @JvmStatic
    fun getSpeed(): Float {
        return speed
    }

    @Volatile private var fuel: Float = 0f

    @JvmStatic
    fun setFuel(value: Float) {
        fuel = value
    }

    @JvmStatic
    fun getFuel(): Float {
        return fuel
    }

    @Volatile private var acceleratorRelativePosition: Float = 0f

    @JvmStatic
    fun setAcceleratorRelativePosition(value: Float) {
        acceleratorRelativePosition = value
    }

    @JvmStatic
    fun getAcceleratorRelativePosition(): Float {
        return acceleratorRelativePosition
    }

    @Volatile private var actualEnginePercentTorque: Float = 0f

    @JvmStatic
    fun setActualEnginePercentTorque(value: Float) {
        actualEnginePercentTorque = value
    }

    @JvmStatic
    fun getActualEnginePercentTorque(): Float {
        return actualEnginePercentTorque
    }

}