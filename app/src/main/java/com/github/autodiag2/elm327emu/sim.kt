package com.github.autodiag2.elm327emu

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import com.github.autodiag2.elm327emu.sim.ecu.EcuCycle
import com.github.autodiag2.elm327emu.sim.ecu.EcuRandom
import com.github.autodiag2.elm327emu.sim.ecu.EcuReplay
import com.github.autodiag2.elm327emu.sim.ecu.EcuGui
import com.github.autodiag2.elm327emu.sim.ecu.EcuScript
import com.github.autodiag2.elm327emu.sim.ecu.getScript
import com.github.autodiag2.elm327emu.sim.ecu.updateScript
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class EcuType(val label: String) {
    GUI("GUI"),
    RANDOM("random"),
    CYCLE("cycle"),
    REPLAY("replay"),
    SCRIPT("Script");

    override fun toString() = label
}

open class EcuConfig(
    val address: Byte,
    var name: String,
    var type: EcuType,
    var screen: View
)
class SimView(
    private val activity: MainActivity
) : FrameLayout(activity) {

    private val ecuListView: ViewGroup
    val ecus = mutableListOf<EcuConfig>()
    private val ecuAddSelect: Spinner

    init {
        LayoutInflater.from(context).inflate(R.layout.sim_main, this, true)
        ecuListView = findViewById(R.id.ecu_list)
        val addEcuBtn = findViewById<Button>(R.id.add_ecu)
        val ecuIdInput = findViewById<EditText>(R.id.ecu_id_input)
        ecuAddSelect = findViewById(R.id.ecu_type_spinner)
        val types = EcuType.values().toList()

        val adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_item,
            types
        )

        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        ecuAddSelect.adapter = adapter
        addEcuBtn.setOnClickListener {
            val type = selectedType()
            val hexStr = ecuIdInput.text.toString().trim()

            val address = try {
                hexStr.toInt(16) and 0xFF
            } catch (e: Exception) {
                Toast.makeText(activity, getString(R.string.sim_main_ecu_config_invalid_hex_id), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            buildAddECUToGUI(address.toByte(), getString(R.string.sim_main_ecu_config_ecu_name, type), type)
        }

        var running = false
        findViewById<Button>(R.id.sim_state).apply {
            setOnClickListener {
                if (activity.isPermissionsGranted()) {
                    running = !running
                    text = if (running) getString(R.string.sim_main_stop_sim) else getString(R.string.sim_main_start_sim)
                    if (running) activity.startServer() else activity.stopServer()
                } else {
                    activity.requestPermissions()
                }
            }
        }
        val address = 0xE8
        buildAddECUToGUI(address.toByte(), getString(R.string.sim_ecu_gui_ecu_name), EcuType.GUI)
    }

    fun saveConfig(path: String) {
        File(path).writeText(saveConfigAsJson())
    }

    fun saveConfigAsJson(): String {
        val root = JSONArray()

        for (ecu in ecus) {
            val obj = JSONObject()

            val address: Byte = ecu.address.toByte()
            obj.put("id", ecu.address)
            obj.put("name", ecu.name)
            obj.put("type", ecu.type.name)

            when (ecu) {
                is EcuGui -> {
                    // GUI state extraction from SimGeneratorGui (global state)
                    val gui = JSONObject()

                    gui.put("ecuName", ecu.getECUName())
                    gui.put("vin", ecu.getVIN())
                    gui.put("mil", ecu.getMILState())
                    gui.put("dtcCleared", ecu.areDTCsCleared())

                    val dtcs = JSONArray()
                    ecu.dtcs.forEach { dtcs.put(it) }
                    gui.put("dtcs", dtcs)

                    // signals
                    val signals = JSONArray()
                    ecu.signals.forEach { entry ->
                        val value = libautodiag.getSignalValue(address, entry.key)
                        if (!value.isNaN()) {
                            val s = JSONObject()
                            s.put("path", entry.key)
                            s.put("value", value)
                            signals.put(s)
                        }
                    }

                    gui.put("signals", signals)
                    obj.put("gui", gui)
                }

                is EcuScript -> {
                    obj.put("script", getScript(ecu))
                }

                is EcuRandom -> {

                }

                is EcuCycle -> {

                }

                is EcuReplay -> {

                }
            }

            root.put(obj)
        }
        return root.toString(2)
    }

    fun loadConfig(path: String) {
        val file = File(path)
        if (!file.exists()) return
        loadConfigJSON(file.readText())
    }

    fun loadConfigJSON(json_text: String) {
        val root = JSONArray(json_text)

        // reset current state
        ecuClear()

        for (i in 0 until root.length()) {
            val obj = root.getJSONObject(i)

            val id = obj.getInt("id")
            val name = obj.getString("name")
            val type = EcuType.valueOf(obj.getString("type"))

            val ecu = buildEcuConfig(id.toByte(), name, type)
            ecus.add(ecu)
            addEcuRow(ecu)

            when (ecu) {
                is EcuGui -> {
                    val gui = obj.optJSONObject("gui") ?: continue

                    val ecu_name = ecu.screen.findViewById<EditText>(R.id.ecu_name)
                    ecu_name.setText(gui.optString("ecuName", ""))
                    val vin = ecu.screen.findViewById<EditText>(R.id.vin)
                    vin.setText(gui.optString("vin", ""))
                    val mil = ecu.screen.findViewById<CheckBox>(R.id.mil_state)
                    mil.isChecked = gui.optBoolean("mil", false)
                    val dtcCleared = ecu.screen.findViewById<CheckBox>(R.id.dtcs_cleared)
                    dtcCleared.isChecked = gui.optBoolean("dtcCleared", false)

                    // dtcs
                    ecu.clearDTCs()
                    val dtcs = gui.optJSONArray("dtcs")
                    if (dtcs != null) {
                        for (j in 0 until dtcs.length()) {
                            ecu.addDtcByCode(dtcs.getString(j))
                        }
                    }

                    // signals
                    ecu.clearSignals()
                    val signals = gui.optJSONArray("signals")
                    if (signals != null) {
                        for (j in 0 until signals.length()) {
                            val s = signals.getJSONObject(j)
                            val path = s.getString("path")
                            val value = s.getDouble("value")

                            ecu.addSignalByPath(path)
                            ecu.setSignalValue(path, value)
                        }
                    }
                }

                is EcuScript -> {
                    val script = obj.optString("script", "")
                    val editor = ecu.screen.findViewById<EditText>(R.id.lua_editor)
                    updateScript(script, ecu)
                    editor.setText(script)
                }

                is EcuRandom -> {

                }

                is EcuCycle -> {

                }

                is EcuReplay -> {

                }
            }
        }
    }

    fun buildEcuConfig(address: Byte, name: String, type: EcuType): EcuConfig {
        return when ( type ) {
            EcuType.GUI -> EcuGui(address, name, activity)
            EcuType.SCRIPT -> EcuScript(address, name, activity)
            EcuType.RANDOM -> EcuRandom(address, name, activity)
            EcuType.CYCLE -> EcuCycle(address, name, activity)
            EcuType.REPLAY -> EcuReplay(address, name, activity)
        }
    }

    fun getString(resId: Int, vararg formatArgs: Any?): String {
        return activity.getString(resId, *formatArgs.map { it ?: "" }.toTypedArray())
    }

    private fun buildAddECUToGUI(address: Byte, name: String, type: EcuType) {

        ecuRemoveByAddress(address)

        val ecu = buildEcuConfig(address, name, type)

        ecus.add(ecu)
        addEcuRow(ecu)
    }

    fun addEcuRow(ecu: EcuConfig) {
        val row = activity.layoutInflater.inflate(R.layout.sim_main_ecu_row, ecuListView, false)
        val cardView = row.findViewById<CardView>(R.id.ecu_row_cardview)
        val title = row.findViewById<TextView>(R.id.ecu_title)

        title.text = "ECU 0x${ecu.address.toString(16).uppercase()} (${ecu.name})"

        row.setOnClickListener {
            activity.showNestedScreen(ecu.screen)
        }

        cardView.setOnLongClickListener {

            val popup = PopupMenu(activity, cardView)
            popup.menuInflater.inflate(R.menu.main_ecu_row, popup.menu)

            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_delete -> {
                        ecuRemoveByAddress(ecu.address)
                        true
                    }
                    else -> false
                }
            }

            popup.show()
            true
        }

        ecuListView.addView(row)
    }


    private fun selectedType(): EcuType {
        val selected = ecuAddSelect.selectedItem
        return selected as EcuType
    }
    fun ecuClear() {
        // iterate backwards to safely remove
        val offset_in_layout = 1
        for (i in ecus.indices.reversed()) {
            val ecu = ecus[i]

            libautodiag.removeEcuByAddress(ecu.address.toByte())
            ecuListView.removeViewAt(i + offset_in_layout)
            ecus.removeAt(i)
        }
    }
    fun ecuRemoveByAddress(address: Byte) {
        // iterate backwards to safely remove
        val offset_in_layout = 1
        for (i in ecus.indices.reversed()) {
            val ecu = ecus[i]

            if (ecu.address.toByte() == address.toByte()) {
                libautodiag.removeEcuByAddress(ecu.address.toByte())
                ecuListView.removeViewAt(i + offset_in_layout)
                ecus.removeAt(i)
            }
        }
    }
}
