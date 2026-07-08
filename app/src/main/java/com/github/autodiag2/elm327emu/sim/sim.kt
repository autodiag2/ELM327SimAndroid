package com.github.autodiag2.elm327emu.sim

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import com.github.autodiag2.elm327emu.LogLevel
import com.github.autodiag2.elm327emu.MainActivity
import com.github.autodiag2.elm327emu.R
import com.github.autodiag2.elm327emu.libautodiag
import com.github.autodiag2.elm327emu.sim.ecu.Ecu
import com.github.autodiag2.elm327emu.sim.ecu.EcuAddress
import com.github.autodiag2.elm327emu.sim.ecu.EcuType
import com.github.autodiag2.elm327emu.ui.JsonConfigurable
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class Sim(
    private val activity: MainActivity
) : FrameLayout(activity), JsonConfigurable {

    private val ecuListView: ViewGroup
    val ecus = mutableListOf<Ecu>()
    private val ecuAddSelect: Spinner
    var running: Boolean = false

    companion object {

        const val SCHEMA: String = "autodiag/sim/any"
        const val SCHEMA_VERSION: Double = 1.0

    }

    init {
        LayoutInflater.from(context).inflate(R.layout.sim, this, true)
        ecuListView = findViewById(R.id.ecu_list)
        val addEcuBtn = findViewById<Button>(R.id.add_ecu)
        val ecuIdInput = findViewById<EditText>(R.id.ecu_id_input)
        ecuAddSelect = findViewById(R.id.ecu_type_spinner)

        val types = EcuType.entries
        val labels = types.map { activity.getString(it.label_id) }

        val adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_item,
            labels
        )

        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        ecuAddSelect.adapter = adapter
        addEcuBtn.setOnClickListener {
            val type = selectedType()
            val hexStr = ecuIdInput.text.toString().trim()

            val address = try {
                hexStr.toInt(16) and 0xFF
            } catch (e: Exception) {
                Toast.makeText(activity, getString(R.string.sim_ecu_config_invalid_hex_id), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            buildAddECUToGUI(address.toByte(), getString(R.string.sim_ecu_config_ecu_name, type), type)
        }

        findViewById<Button>(R.id.sim_state).apply {
            setOnClickListener {
                if (activity.isPermissionsGranted()) {
                    running = !running
                    text = if (running) getString(R.string.sim_stop_sim) else getString(R.string.sim_start_sim)
                    if (running) activity.startServer() else activity.stopServer()
                } else {
                    activity.requestPermissions()
                }
            }
        }
        buildAddECUToGUI(Ecu.DEFAULT_ADDRESS, getString(R.string.sim_ecu_gui_ecu_name), EcuType.gui)
    }

    public fun isRunning(): Boolean {
        return running
    }

    fun saveConfig(path: String) {
        File(path).writeText(saveConfigAsJson())
    }

    fun saveConfigAsJson(): String {
        val desc = toJson()
        return desc.toString(2)
    }

    override fun toJson(): JSONObject {
        val desc = JSONObject()

        desc.put("schema", SCHEMA)
        desc.put("version", SCHEMA_VERSION)

        val content = JSONArray()
        desc.put("content", content)

        for (ecu in ecus) {
            content.put(ecu.toJson())
        }

        return desc
    }

    fun loadConfig(path: String) {
        val file = File(path)
        if (!file.exists()) return
        loadConfigJSON(file.readText())
    }

    fun loadConfigJSON(json_text: String) {
        val desc = JSONObject(json_text)
        fromJson(desc)
    }

    override fun fromJson(desc: JSONObject) {
        val schema = desc.optString("schema")

        if(schema.isEmpty() || !schema.startsWith(SCHEMA)) {
            activity.appendLog(
                getString(R.string.sim_invalid_ecu_schema, schema),
                LogLevel.ERROR
            )
            return
        }

        val schemaVersion = desc.optDouble("version")
        if ( schemaVersion != SCHEMA_VERSION ) {
            activity.appendLog(
                getString(R.string.sim_unsupported_ecu_schema_version, schemaVersion),
                LogLevel.ERROR
            )
            return
        }

        val content = desc.optJSONArray("content")
        if ( content == null ) {
            activity.appendLog(
                getString(R.string.sim_no_content, schemaVersion),
                LogLevel.ERROR
            )
            return
        }

        // reset current state
        ecuClear()

        for (i in 0 until content.length()) {
            val ecuDesc = content.getJSONObject(i)

            val ecu = Ecu.createFromJSON(ecuDesc, activity)
            if ( ecu != null ) {
                ecus.add(ecu)
                addEcuRow(ecu)
            }
        }
    }

    fun getString(resId: Int, vararg formatArgs: Any?): String {
        return activity.getString(resId, *formatArgs.map { it ?: "" }.toTypedArray())
    }

    private fun buildAddECUToGUI(address: EcuAddress, name: String, type: EcuType) {

        ecuRemoveByAddress(address)

        val ecu = Ecu.create(type, activity, address, name)

        ecus.add(ecu)
        addEcuRow(ecu)
    }

    fun addEcuRow(ecu: Ecu) {
        val row = activity.layoutInflater.inflate(R.layout.sim_item, ecuListView, false)
        val cardView = row.findViewById<CardView>(R.id.ecu_row_cardview)
        val title = row.findViewById<TextView>(R.id.ecu_title)

        title.text = "ECU 0x${ecu.address.toUByte().toString(16).uppercase()} (${ecu.displayName})"

        row.setOnClickListener {
            activity.showNestedScreen(ecu)
        }

        cardView.setOnLongClickListener {

            val popup = PopupMenu(activity, cardView)
            popup.menuInflater.inflate(R.menu.sim_ecu_row, popup.menu)

            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.sim_list_menu_delete -> {
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


    private fun selectedType(): EcuType = EcuType.entries[ecuAddSelect.selectedItemPosition]

    fun ecuClear() {
        // iterate backwards to safely remove
        val offset_in_layout = 1
        for (i in ecus.indices.reversed()) {
            val ecu = ecus[i]

            libautodiag.removeEcuByAddress(ecu.address)
            ecuListView.removeViewAt(i + offset_in_layout)
            ecus.removeAt(i)
        }
    }
    fun ecuRemoveByAddress(address: EcuAddress) {
        // iterate backwards to safely remove
        val offset_in_layout = 1
        for (i in ecus.indices.reversed()) {
            val ecu = ecus[i]

            if (ecu.address == address) {
                libautodiag.removeEcuByAddress(ecu.address)
                ecuListView.removeViewAt(i + offset_in_layout)
                ecus.removeAt(i)
            }
        }
    }
}
