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

object SimGeneratorGui {
    @Volatile var mil = false
    @Volatile var dtcCleared = false
    @Volatile var ecuName = "ECU from gui"
    @Volatile var vin = "VF7RD5FV8FL507366"
    val dtcs = mutableListOf<String>()

    private val numericSignals = linkedMapOf<String, Double>()

    @Synchronized
    fun setSignalValue(path: String, value: Double) {
        numericSignals[path] = value
    }

    @Synchronized
    fun getSignalValue(path: String): Double? {
        return numericSignals[path]
    }

    @Synchronized
    fun clearDynamicSignals() {
        numericSignals.clear()
    }
}

object MainActivityRef {
    @Volatile var activity: MainActivity? = null
}

object libautodiag {
    init {
        System.loadLibrary("autodiag")
    }

    @JvmStatic external fun launchEmu(tmpDirPath: String, kind: String = "socket"): String
    @JvmStatic fun getMil(): Boolean = SimGeneratorGui.mil
    @JvmStatic fun getDtcCleared(): Boolean = SimGeneratorGui.dtcCleared
    @JvmStatic fun getEcuName(): String = SimGeneratorGui.ecuName
    @JvmStatic fun getVin(): String = SimGeneratorGui.vin
    @JvmStatic fun getDtcs(): Array<String> = SimGeneratorGui.dtcs.toTypedArray()

    @JvmStatic
    fun getSignalValue(path: String): Double {
        return SimGeneratorGui.getSignalValue(path) ?: Double.NaN
    }

    @JvmStatic
    fun setSignalValue(path: String, value: Double) {
        SimGeneratorGui.setSignalValue(path, value)
    }

    @JvmStatic
    fun setDtcCleared(value: Boolean) {
        SimGeneratorGui.dtcCleared = value
        MainActivityRef.activity?.runOnUiThread {
            MainActivityRef.activity?.setDtcClearedUi(value)
        }
    }

    @JvmStatic external fun getProtocols(): Array<String>
    @JvmStatic external fun setProtocol(protocol: Int)
    @JvmStatic external fun getProtocol(): Int

    @JvmStatic external fun getSimSignals(): Array<SimSignal>
    @JvmStatic
    external fun setResponseByteArrayByAddress(
        address: Byte,
        callback: EcuByteArrayHandler
    )
}