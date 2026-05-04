package com.github.autodiag2.elm327emu

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

    data class EcuState(
        val address: Byte,
        var mil: Boolean = false,
        var dtcCleared: Boolean = false,
        var ecuName: String = "ECU from gui",
        var vin: String = "VF7RD5FV8FL507366",
        val dtcs: MutableList<String> = mutableListOf(),
        val signals: MutableMap<String, Double> = linkedMapOf(),
    )

    private val ecus = mutableMapOf<Byte, EcuState>()

    fun clear() {
        ecus.clear()
    }

    @Synchronized
    fun getOrCreate(address: Byte): EcuState {
        return ecus.getOrPut(address) { EcuState(address) }
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
        return SimGeneratorGuiManager.getOrCreate(address).mil
    }
    @JvmStatic fun getDtcCleared(address: Byte): Boolean {
        return SimGeneratorGuiManager.getOrCreate(address).dtcCleared
    }
    @JvmStatic fun getEcuName(address: Byte): String {
        return SimGeneratorGuiManager.getOrCreate(address).ecuName
    }
    @JvmStatic fun getVin(address: Byte): String {
        return SimGeneratorGuiManager.getOrCreate(address).vin
    }
    @JvmStatic fun getDtcs(address: Byte): Array<String> {
        return SimGeneratorGuiManager.getOrCreate(address).dtcs.toTypedArray()
    }

    @JvmStatic
    fun getSignalValue(address: Byte, path: String): Double {
        return SimGeneratorGuiManager.getOrCreate(address)
            .signals[path] ?: Double.NaN
    }

    @JvmStatic
    fun setSignalValue(address: Byte, path: String, value: Double) {
        SimGeneratorGuiManager.getOrCreate(address)
            .signals[path] = value
    }

    @JvmStatic
    fun setDtcCleared(address: Byte, value: Boolean) {
        SimGeneratorGuiManager.getOrCreate(address)
            .dtcCleared = value
        MainActivityRef.activity?.runOnUiThread {
            MainActivityRef.activity?.setDtcClearedUi(address, value)
        }
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