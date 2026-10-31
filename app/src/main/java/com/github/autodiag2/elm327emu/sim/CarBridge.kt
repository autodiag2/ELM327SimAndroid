package com.github.autodiag2.elm327emu.sim

import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap

object CarBridge {
    private val signals = ConcurrentHashMap<String, Double>()
    private val tpmsSignals = ConcurrentHashMap<String, Double>()

    private val tpmsPositions = listOf("fl", "fr", "rl", "rr", "sp")

    init {
        tpmsPositions.forEach { position ->
            tpmsSignals["${position}_pressure_psi"] = 55.0
            tpmsSignals["${position}_temperature_c"] = 55.0
        }
    }

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
    fun setTpmsSensor(position: String, pressurePsi: Double, temperatureC: Double) {
        val key = position.lowercase()
        require(key in tpmsPositions)
        tpmsSignals["${key}_pressure_psi"] = pressurePsi
        tpmsSignals["${key}_temperature_c"] = temperatureC
    }

    @JvmStatic
    fun getTpmsSignal(name: String): Double {
        return tpmsSignals[name] ?: 0.0
    }

    @JvmStatic
    fun hasTpmsSignal(name: String): Boolean {
        return tpmsSignals.containsKey(name)
    }

    @JvmStatic
    fun getTpmsSignalsPath(): Enumeration<String> {
        return tpmsSignals.keys()
    }

    @JvmStatic
    fun clear() {
        signals.clear()
        tpmsSignals.clear()
        tpmsPositions.forEach { position ->
            tpmsSignals["${position}_pressure_psi"] = 55.0
            tpmsSignals["${position}_temperature_c"] = 55.0
        }
    }
}