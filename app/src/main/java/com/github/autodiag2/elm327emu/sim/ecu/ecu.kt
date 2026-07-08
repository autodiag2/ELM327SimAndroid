package com.github.autodiag2.elm327emu.sim.ecu

import android.content.Context
import androidx.constraintlayout.widget.ConstraintLayout
import com.github.autodiag2.elm327emu.LogLevel
import com.github.autodiag2.elm327emu.MainActivity
import com.github.autodiag2.elm327emu.MainActivityRef.activity
import com.github.autodiag2.elm327emu.R
import com.github.autodiag2.elm327emu.ui.JsonConfigurable
import com.github.autodiag2.elm327emu.ui.NestedScreen
import org.json.JSONObject
import com.github.autodiag2.elm327emu.libautodiag

typealias EcuAddress = Byte

abstract class Ecu(
    val type: EcuType,
    var address: EcuAddress = DEFAULT_ADDRESS,
    var displayName: String = type.toString(),
    context: Context
) : ConstraintLayout(context), JsonConfigurable, NestedScreen {

    override fun onBack() {        

    }

    protected open fun loadStateFromGeneratorWhenShowing() {
        val json: String = libautodiag.simEcuToJson(address.toByte())
        fromJson(JSONObject(json))
    }

    override fun onShow() {
        loadStateFromGeneratorWhenShowing()
    }

    companion object {

        const val DEFAULT_ADDRESS: EcuAddress = 0xE8.toByte()
        const val SCHEMA: String = "autodiag/sim/ecu"
        const val SCHEMA_VERSION: Double = 1.0

        fun create(type: EcuType, activity: MainActivity, address: EcuAddress = DEFAULT_ADDRESS, displayName: String = type.toString()) : Ecu {
            return when ( type ) {
                EcuType.gui -> EcuGui(address, displayName, activity)
                EcuType.bytearray -> EcuScript(address, displayName, activity)
                EcuType.random -> EcuRandom(address, displayName, activity)
                EcuType.cycle -> EcuCycle(address, displayName, activity)
                EcuType.replay -> EcuReplay(address, displayName, activity)
                EcuType.citroen_c5_x7 -> EcuCitroenC5X7(address, displayName, activity)
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
            activity.appendLog("schema = ${schema} typeName = ${typeName}")
            var type = try {
                EcuType.valueOf(typeName)
            } catch (e: IllegalArgumentException) {
                activity.appendLog(
                    activity.getString(R.string.sim_ecu_unknown_ecu_type, typeName),
                    LogLevel.ERROR
                )
                return null
            }
            type = type!!

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

            ecu.fromJson(desc)

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

        var type = try {
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
        type = type!!

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
        libautodiag.simEcuLoadFromJson(address.toByte(), desc.toString())

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

enum class EcuType(val label_id: Int) {
    gui(R.string.sim_ecu_type_gui),
    random(R.string.sim_ecu_type_random),
    cycle(R.string.sim_ecu_type_cycle),
    replay(R.string.sim_ecu_type_replay),
    citroen_c5_x7(R.string.sim_ecu_type_citroen_c5_x7),
    bytearray(R.string.sim_ecu_type_bytearray);

    companion object {
        fun labels(): List<Int> = entries.map { it.label_id }
    }

}