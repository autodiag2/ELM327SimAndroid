package com.github.autodiag2.elm327emu

import com.github.autodiag2.elm327emu.sim.ecu.EcuByteArrayHandler
import com.github.autodiag2.elm327emu.sim.ecu.EcuGuiView

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

    private val ecus = mutableMapOf<Byte, EcuGuiView>()

    fun clear() {
        ecus.clear()
    }

    @Synchronized
    fun add(address: Byte, view: EcuGuiView) {
        ecus[address] = view
    }

    @Synchronized
    fun getBy(address: Byte): EcuGuiView? {
        return ecus[address]
    }

    @Synchronized
    fun remove(address: Byte) {
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

    @JvmStatic fun getMil(address: Byte): Boolean {
        return SimGeneratorGuiManager.getBy(address)?.getMILState() ?: false
    }
    @JvmStatic fun getDtcCleared(address: Byte): Boolean {
        return SimGeneratorGuiManager.getBy(address)?.areDTCsCleared() ?: false
    }
    @JvmStatic fun getEcuName(address: Byte): String {
        return SimGeneratorGuiManager.getBy(address)?.getECUName() ?: "error"
    }
    @JvmStatic fun getVin(address: Byte): String {
        return SimGeneratorGuiManager.getBy(address)?.getVIN() ?: "error"
    }
    @JvmStatic fun getDtcs(address: Byte): Array<String> {
        return SimGeneratorGuiManager.getBy(address)?.dtcs?.toTypedArray() ?: emptyArray()
    }

    @JvmStatic
    fun getSignalValue(address: Byte, path: String): Double {
        return SimGeneratorGuiManager.getBy(address)?.signals?.get(path) ?: Double.NaN
    }

    @JvmStatic
    fun setDtcCleared(address: Byte, value: Boolean) {
        SimGeneratorGuiManager.getBy(address)?.setDTCsCleared(value)
    }

    @JvmStatic external fun launchEmu(tmpDirPath: String, kind: String = "socket"): String
    @JvmStatic external fun getProtocols(): Array<String>
    @JvmStatic external fun setProtocol(protocol: Int)
    @JvmStatic external fun getProtocol(): Int

    @JvmStatic external fun getSimSignals(): Array<SimSignal>
    @JvmStatic
    external fun setResponseByteArrayByAddress(
        address: Byte,
        callback: EcuByteArrayHandler
    )
    @JvmStatic external fun removeEcuByAddress(address: Byte)
    @JvmStatic external fun setResponseGuiByAddress(address: Byte)
}