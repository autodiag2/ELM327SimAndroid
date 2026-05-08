package com.github.autodiag2.elm327emu.sim.ecu

import android.content.Context
import androidx.constraintlayout.widget.ConstraintLayout
import com.github.autodiag2.elm327emu.LogLevel
import com.github.autodiag2.elm327emu.MainActivity
import com.github.autodiag2.elm327emu.MainActivityRef.activity
import com.github.autodiag2.elm327emu.R
import com.github.autodiag2.elm327emu.ui.JsonConfigurable
import org.json.JSONObject

typealias EcuAddress = Byte

abstract class Ecu(
    val type: EcuType,
    var address: EcuAddress = DEFAULT_ADDRESS,
    var displayName: String = type.toString(),
    context: Context
) : ConstraintLayout(context), JsonConfigurable {

    companion object {

        const val DEFAULT_ADDRESS: EcuAddress = 0xE8.toByte()
        const val SCHEMA: String = "autodiag/sim/ecu"
        const val SCHEMA_VERSION: Double = 1.0

        fun create(type: EcuType, activity: MainActivity, address: EcuAddress = DEFAULT_ADDRESS, displayName: String = type.toString()) : Ecu {
            return when ( type ) {
                EcuType.GUI -> EcuGui(address, displayName, activity)
                EcuType.SCRIPT -> EcuScript(address, displayName, activity)
                EcuType.RANDOM -> EcuRandom(address, displayName, activity)
                EcuType.CYCLE -> EcuCycle(address, displayName, activity)
                EcuType.REPLAY -> EcuReplay(address, displayName, activity)
                EcuType.CitroenC5X7 -> EcuCitroenC5X7(address, displayName, activity)
            }
        }
        fun createFromJSON(desc: JSONObject, activity: MainActivity): Ecu? {

            val schema = desc.optString("schema")

            if(schema.isEmpty() || !schema.startsWith(SCHEMA)) {
                activity.appendLog(
                    activity.getString(R.string.sim_ecu_invalid_ecu_schema, schema),
                    LogLevel.ERROR
                )
                return null
            }
            val schemaVersion = desc.optDouble("version")
            if ( schemaVersion != SCHEMA_VERSION ) {
                activity.appendLog(
                    activity.getString(R.string.sim_ecu_unsupported_ecu_schema_version, schemaVersion),
                    LogLevel.ERROR
                )
                return null
            }

            val typeName = schema.removePrefix("${SCHEMA}/")

            val type = try {
                EcuType.valueOf(typeName)
            } catch (e: IllegalArgumentException) {
                activity.appendLog(
                    activity.getString(R.string.sim_ecu_unknown_ecu_type, typeName),
                    LogLevel.ERROR
                )
                return null
            }

            val content = desc.optJSONObject("content")
            if ( content == null ) {
                activity.appendLog(
                    activity.getString(R.string.sim_ecu_no_content),
                    LogLevel.ERROR
                )
                return null
            }
            val address = content.optInt("address", DEFAULT_ADDRESS.toInt()).toByte()
            val displayName = content.optString("displayName", type.toString())
            val ecu = create(type, activity, address, displayName)

            ecu.fromJson(content)

            return ecu
        }
    }

    override fun fromJson(desc: JSONObject) {
        val schema = desc.optString("schema")

        if(schema.isEmpty() || !schema.startsWith(SCHEMA)) {
            activity?.getString(R.string.sim_ecu_invalid_ecu_schema, schema)?.let {
                activity?.appendLog(
                    it,
                    LogLevel.ERROR
                )
            }
            return
        }

        val schemaVersion = desc.optDouble("version")
        if ( schemaVersion != SCHEMA_VERSION ) {
            activity?.getString(R.string.sim_ecu_unsupported_ecu_schema_version, schemaVersion)?.let {
                activity?.appendLog(
                    it,
                    LogLevel.ERROR
                )
            }
            return
        }

        val typeName = schema.removePrefix("${SCHEMA}/")

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

        val content = desc.optJSONObject("content")
        if ( content == null ) {
            activity?.getString(R.string.sim_ecu_no_content)?.let {
                activity?.appendLog(
                    it,
                    LogLevel.ERROR
                )
            }
            return
        }
        val address = content.optInt("address", DEFAULT_ADDRESS.toInt()).toByte()
        val displayName = content.optString("displayName", type.toString())
        // TODO

        fromJsonInternal(content)

    }

    protected open fun fromJsonInternal(content: JSONObject) {

    }

    override fun toJson(): JSONObject {
        val desc = JSONObject()

        desc.put("schema", "${SCHEMA}/${type.name}")
        desc.put("version", SCHEMA_VERSION)

        val content = JSONObject()
        desc.put("content", content)

        content.put("address", address)
        content.put("displayName", displayName)
        val objSpecialization = toJsonInternal()
        objSpecialization.keys().forEach { key ->
            content.put(key, objSpecialization.get(key))
        }
        return desc
    }

    protected open fun toJsonInternal(): JSONObject {
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