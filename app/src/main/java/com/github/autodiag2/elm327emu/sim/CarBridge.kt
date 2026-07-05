package com.github.autodiag2.elm327emu.sim

import java.util.concurrent.ConcurrentHashMap
import java.util.Enumeration

object CarBridge {

    private val signals = ConcurrentHashMap<String, Double>()

    @JvmStatic
    fun setSignal(name: String, value: Double) {
        signals[name] = value
    }

    @JvmStatic
    fun getSignal(name: String): Double {
        return signals[name] ?: 0.0
    }

    @JvmStatic
    fun hasSignal(name: String): Boolean {
        return signals.containsKey(name)
    }

    @JvmStatic
    fun getSignalsPath(): Enumeration<String> {
        return signals.keys()
    }

    @JvmStatic
    fun clear() {
        signals.clear()
    }
}