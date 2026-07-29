package com.github.autodiag2.elm327emu

import com.github.autodiag2.elm327emu.sim.ecu.EcuAddress
import com.github.autodiag2.elm327emu.sim.ecu.EcuByteArrayHandler
import com.github.autodiag2.elm327emu.sim.ecu.EcuGui

enum class IgnitionState(val value: Int) {
    OFF(0),
    ON(1);
    
    companion object {
        fun fromValue(value: Int): IgnitionState =
            entries.firstOrNull { it.value == value } ?: OFF
    }
}

data class SimSignal(
    val path: String,
    val name: String,
    val unit: String?,
    val min: Double,
    val max: Double,
    val step: Double,
    val category: String?
)

object SimGeneratorGuiManager {

    private val ecus = mutableMapOf<EcuAddress, EcuGui>()

    fun clear() {
        ecus.clear()
    }

    @Synchronized
    fun add(address: EcuAddress, view: EcuGui) {
        ecus[address] = view
    }

    @Synchronized
    fun getBy(address: EcuAddress): EcuGui? {
        return ecus[address]
    }

    @Synchronized
    fun remove(address: EcuAddress) {
        ecus.remove(address)
    }
}

object MainActivityRef {
    @Volatile var activity: MainActivity? = null
}

object libautodiag {
    init {
        System.loadLibrary("autodiag")
    }

    @JvmStatic
    fun getMil(address: EcuAddress): Boolean {
        return SimGeneratorGuiManager.getBy(address)?.getMILState() ?: false
    }

    @JvmStatic
    fun getDtcCleared(address: EcuAddress): Boolean {
        return SimGeneratorGuiManager.getBy(address)?.areDTCsCleared() ?: false
    }

    @JvmStatic
    fun getEcuName(address: EcuAddress): String {
        return SimGeneratorGuiManager.getBy(address)?.getECUName() ?: "error"
    }

    @JvmStatic
    fun getVin(address: EcuAddress): String {
        return SimGeneratorGuiManager.getBy(address)?.getVIN() ?: "error"
    }

    @JvmStatic
    fun getDtcs(address: EcuAddress): Array<String> {
        return SimGeneratorGuiManager.getBy(address)?.dtcs?.toTypedArray() ?: emptyArray()
    }

    @JvmStatic
    fun getSignalValue(address: EcuAddress, path: String): Double {
        return SimGeneratorGuiManager.getBy(address)?.signals?.get(path) ?: Double.NaN
    }

    @JvmStatic
    fun setDtcCleared(address: EcuAddress, value: Boolean) {
        SimGeneratorGuiManager.getBy(address)?.setDTCsCleared(value)
    }

    @JvmStatic
    external fun launchEmu(tmpDirPath: String, kind: String = "socket"): String
    @JvmStatic
    external fun getProtocols(): Array<String>
    @JvmStatic
    external fun setProtocol(protocol: Int)
    @JvmStatic
    external fun getProtocol(): Int

    @JvmStatic
    external fun getSimSignals(): Array<SimSignal>

    @JvmStatic
    external fun setResponseByteArrayByAddress(
        address: EcuAddress,
        callback: EcuByteArrayHandler
    )
    public fun interface SignalReceivedHandler {
        fun onSignalReceived(signal: SimSignal, value: Double)
    }

    @JvmStatic
    external fun registerOnSignalReceived(
        address: EcuAddress,
        callback: SignalReceivedHandler
    )

    @JvmStatic
    external fun simEcuToJson(
        address: EcuAddress
    ) : String
    
    @JvmStatic
    external fun simEcuLoadFromJson(
        address: EcuAddress,
        json: String
    )

    @JvmStatic
    external fun setIgnitionState(state: Int)

    fun setIgnitionState(state: IgnitionState) {
        setIgnitionState(state.value)
    }
    
    @JvmStatic
    external fun getIgnitionState(): Int

    fun getIgnitionStateAs(): IgnitionState {
        return IgnitionState.fromValue(getIgnitionState())
    }

    @JvmStatic
    external fun removeEcuByAddress(address: EcuAddress)
    @JvmStatic
    external fun setResponseGuiByAddress(address: EcuAddress)
    @JvmStatic
    external fun setResponseTypeContextByAddress(
        address: EcuAddress,
        type: String,
        context: String
    )
    @JvmStatic
    external fun setResponseTypeByAddress(
        address: EcuAddress,
        type: String
    )
}