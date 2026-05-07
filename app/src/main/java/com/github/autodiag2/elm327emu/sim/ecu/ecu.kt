package com.github.autodiag2.elm327emu.sim.ecu

import android.content.Context
import androidx.constraintlayout.widget.ConstraintLayout
import com.github.autodiag2.elm327emu.LogLevel
import com.github.autodiag2.elm327emu.MainActivity
import com.github.autodiag2.elm327emu.MainActivityRef.activity
import com.github.autodiag2.elm327emu.R
import org.json.JSONObject

val EcuDefaultAddress = 0xE8

abstract class Ecu(
    val type: EcuType,
    var address: Byte = EcuDefaultAddress.toByte(),
    var displayName: String = type.toString(),
    context: Context
) : ConstraintLayout(context) {

    companion object {
        fun create(type: EcuType, activity: MainActivity, address: Byte = EcuDefaultAddress.toByte(), displayName: String = type.toString()) : Ecu {
            return when ( type ) {
                EcuType.GUI -> EcuGui(address, displayName, activity)
                EcuType.SCRIPT -> EcuScript(address, displayName, activity)
                EcuType.RANDOM -> EcuRandom(address, displayName, activity)
                EcuType.CYCLE -> EcuCycle(address, displayName, activity)
                EcuType.REPLAY -> EcuReplay(address, displayName, activity)
                EcuType.CitroenC5X7 -> EcuCitroenC5X7(address, displayName, activity)
            }
        }
        fun createFromJSON(obj: JSONObject, activity: MainActivity): Ecu {

            val schema = obj.getString("schema")
            val prefix = "autodiag/sim/ecu/"

            if(!schema.startsWith(prefix)) {
                activity.appendLog(
                    activity.getString(R.string.sim_ecu_invalid_ecu_schema, schema),
                    LogLevel.ERROR
                )
                return create(EcuType.RANDOM, activity)
            }

            val typeName = schema.removePrefix(prefix)

            val type = try {
                EcuType.valueOf(typeName)
            } catch (e: IllegalArgumentException) {
                activity.appendLog(
                    activity.getString(R.string.sim_ecu_unknown_ecu_type, typeName),
                    LogLevel.ERROR
                )
                activity.appendLog("", LogLevel.ERROR)
                return create(EcuType.RANDOM, activity)
            }

            val address = obj.optInt("address", EcuDefaultAddress).toByte()
            val displayName = obj.optString("displayName", type.toString())
            val ecu = create(type, activity, address, displayName)

            ecu.stateFromJson(obj)

            return ecu
        }
    }

    fun stateFromJson(obj: JSONObject) {
        val schema = obj.getString("schema")
        val prefix = "autodiag/sim/ecu/"

        if(!schema.startsWith(prefix)) {
            activity?.getString(R.string.sim_ecu_invalid_ecu_schema, schema)?.let {
                activity?.appendLog(
                    it,
                    LogLevel.ERROR
                )
            }
            return
        }

        val typeName = schema.removePrefix(prefix)

        val type = try {
            EcuType.valueOf(typeName)
        } catch (e: IllegalArgumentException) {
            activity?.getString(R.string.sim_ecu_unknown_ecu_type, typeName)?.let {
                activity?.appendLog(
                    it,
                    LogLevel.ERROR
                )
            }
            return
        }

        val address = obj.optInt("address", EcuDefaultAddress).toByte()
        val displayName = obj.optString("displayName", type.toString())
        // TODO

        stateFromJsonInternal(obj.getJSONObject("child"))

    }

    protected open fun stateFromJsonInternal(obj: JSONObject) {

    }

    fun stateAsJson(): JSONObject {
        val obj = JSONObject()

        obj.put("schema", "autodiag/sim/ecu/${type.name}")
        obj.put("address", address)
        obj.put("displayName", displayName)

        val objSpecialization = stateAsJsonInternal()
        obj.put("child", objSpecialization)
        return obj
    }

    protected open fun stateAsJsonInternal(): JSONObject {
        return JSONObject()
    }

}

enum class EcuType(val label: String) {
    GUI("GUI"),
    RANDOM("random"),
    CYCLE("cycle"),
    REPLAY("replay"),
    CitroenC5X7("Citroen C5 X7"),
    SCRIPT("Script");

    override fun toString() = label
}