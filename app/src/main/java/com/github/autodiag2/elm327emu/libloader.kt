package com.github.autodiag2.elm327emu

object SimGeneratorGui {
    @Volatile var vehicleSpeed = 0
    @Volatile var coolantTemp = -40
    @Volatile var engineRpm = 0
    @Volatile var mil = false
    @Volatile var dtcCleared = false
    @Volatile var ecuName = "ECU from gui"
    @Volatile var vin = "VF7RD5FV8FL507366"
    val dtcs = mutableListOf<String>()
}

object MainActivityRef {
    @Volatile var activity: MainActivity? = null
}

object libautodiag {
    init {
        System.loadLibrary("autodiag")
    }
    @JvmStatic external fun launchEmu(tmpDirPath: String, kind: String = "socket"): String

    @JvmStatic fun getVehicleSpeed(): Int = SimGeneratorGui.vehicleSpeed
    @JvmStatic fun getCoolantTemp(): Int = SimGeneratorGui.coolantTemp
    @JvmStatic fun getEngineRpm(): Int = SimGeneratorGui.engineRpm
    @JvmStatic fun getMil(): Boolean = SimGeneratorGui.mil
    @JvmStatic fun getDtcCleared(): Boolean = SimGeneratorGui.dtcCleared
    @JvmStatic fun getEcuName(): String = SimGeneratorGui.ecuName
    @JvmStatic fun getVin(): String = SimGeneratorGui.vin
    @JvmStatic fun getDtcs(): Array<String> = SimGeneratorGui.dtcs.toTypedArray()
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
}
