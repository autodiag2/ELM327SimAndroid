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
import android.widget.ToggleButton
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
import com.github.autodiag2.elm327emu.IgnitionState
import com.github.autodiag2.elm327emu.sim.serial.CustomController
import android.widget.AdapterView
import android.view.View
import android.util.Log
import com.github.autodiag2.elm327emu.BuildConfig
import com.github.autodiag2.elm327emu.com.Bridge
import com.github.autodiag2.elm327emu.com.BLEBridge
import com.github.autodiag2.elm327emu.com.BluetoothBridge
import com.github.autodiag2.elm327emu.com.NetworkBridge
import com.github.autodiag2.elm327emu.com.BridgeOrchestrator
import android.app.AlertDialog
import android.widget.ImageButton

class Sim(
    private val activity: MainActivity
) : FrameLayout(activity), JsonConfigurable, CustomController.Listener {

    public val customSerialScreen: CustomController
    var customSerialScreenIsChecked: Boolean = false
    private val ecuListView: ViewGroup
    val ecus = mutableListOf<Ecu>()
    private val ecuAddSelect: Spinner
    var running: Boolean = false
    private lateinit var connectedClientsButton: Button

    override fun customSerialOnRunStateChange(newState: Boolean) {
        customSerialScreenIsChecked = newState
        updateCustomSerialScriptPlayPause()
    }

    companion object {

        const val SCHEMA: String = "autodiag/sim/any"
        const val SCHEMA_VERSION: Double = 1.0

    }

    private data class CustomSerialExample(
        val name: String,
        val resourceId: Int
    )

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

            buildAddECUToGUI(address.toByte(), getString(R.string.sim_ecu_config_ecu_name, getString(type.label_id)), type)
        }

        connectedClientsButton =
            findViewById(R.id.sim_connected_clients)

        connectedClientsButton.setOnClickListener {
            showConnectedClients()
        }
        updateConnectedClientsButton()
        customSerialScreen = CustomController(activity, listener = this)
        customSerialScreen.setReplayEntriesProvider {
            activity.logView.logRepo.buffer
        }
        findViewById<Button>(R.id.sim_custom_serial_script_open).setOnClickListener {
            activity.showNestedScreen(customSerialScreen)
        }
        
        findViewById<Button>(R.id.sim_state).apply {
            setOnClickListener {
                if (activity.isPermissionsGranted()) {
                    running = !running

                    text = if (running) {
                        getString(R.string.sim_stop_sim)
                    } else {
                        getString(R.string.sim_start_sim)
                    }

                    val colorAttr =
                        if (running) {
                            R.attr.colorAccentSuccess
                        } else {
                            R.attr.colorAccentInProgress
                        }

                    val typedValue = android.util.TypedValue()
                    activity.theme.resolveAttribute(
                        colorAttr,
                        typedValue,
                        true
                    )

                    backgroundTintList =
                        android.content.res.ColorStateList.valueOf(
                            typedValue.data
                        )

                    if (running) {
                        activity.startServer()
                    } else {
                        activity.stopServer()
                    }
                } else {
                    activity.requestPermissions()
                }
            }
        }
        findViewById<ImageButton>(
            R.id.sim_custom_serial_script_play_pause
        ).setOnClickListener {
            customSerialScreenIsChecked = !customSerialScreenIsChecked
            updateCustomSerialScriptPlayPause()
            onRunStateChange()
        }
        findViewById<ToggleButton>(R.id.ignition_state).apply {
            isChecked = libautodiag.getIgnitionStateAs() == IgnitionState.ON

            setOnCheckedChangeListener { _, isChecked ->
                libautodiag.setIgnitionState(
                    if (isChecked) IgnitionState.ON else IgnitionState.OFF
                )
            }
        }
        buildAddECUToGUI(Ecu.DEFAULT_ADDRESS, getString(R.string.sim_ecu_gui_ecu_name), EcuType.gui)
        setupCustomSerialScripts()
    }

    private fun bridgeType(bridge: Bridge): String {
        return when (bridge) {
            is com.github.autodiag2.elm327emu.com.NetworkBridge ->
                getString(R.string.com_bridge_nt)

            is com.github.autodiag2.elm327emu.com.BluetoothBridge ->
                getString(R.string.com_bridge_bt_classic)

            is com.github.autodiag2.elm327emu.com.BLEBridge ->
                getString(R.string.com_bridge_ble)

            else ->
                bridge.javaClass.simpleName
        }
    }

    public fun updateConnectedClientsButton() {
        post {
            val nclient = activity.clients?.size ?: 0
            connectedClientsButton.text = getString(R.string.sim_connected_clients_format, nclient)
            val colorAttr =
                if ( nclient == 1 ) {
                    R.attr.colorAccentSuccess
                } else {
                    R.attr.colorAccentInProgress
                }
            val typedValue = android.util.TypedValue()
            activity.theme.resolveAttribute(
                colorAttr,
                typedValue,
                true
            )
            connectedClientsButton.backgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    typedValue.data
                )
        }
    }

    private fun showConnectedClients() {
        val clients = activity.clients.orEmpty()
        val view =
            activity.layoutInflater.inflate(
                R.layout.sim_connected_clients,
                null
            )
        val list =
            view.findViewById<ViewGroup>(
                R.id.sim_connected_clients_list
            )

        for (client in clients) {
            val textView =
                TextView(activity)
            textView.text =
                "${bridgeType(client.bridge)}: ${client.clientIdentifier}"
            textView.setPadding(8,8,8,8)
            list.addView(textView)
        }
        AlertDialog.Builder(activity)
            .setTitle(
                getString(
                    R.string.sim_connected_clients_dialog_title
                )
            )
            .setView(view)
            .setNegativeButton(
                android.R.string.cancel,
                null
            )
            .show()
    }

    public fun logDebug(
        message: String
    ) {
        if (BuildConfig.DEBUG) {
            Log.d(
                "sim.Sim",
                message
            )
        }
    }

    private fun updateCustomSerialScriptPlayPause() {
        val button =
            findViewById<ImageButton>(
                R.id.sim_custom_serial_script_play_pause
            )

        if (customSerialScreenIsChecked) {
            button.setImageResource(R.drawable.ic_pause)
            button.contentDescription =
                getString(R.string.sim_custom_serial_script_pause)
        } else {
            button.setImageResource(R.drawable.ic_play)
            button.contentDescription =
                getString(R.string.sim_custom_serial_script_play)
        }
    }

    fun onRunStateChange() {
        logDebug("Running state of the sim : ${running}")
        if ( running ) {
            customSerialScreen.onRunStateChange(customSerialScreenIsChecked)
        } else {
            customSerialScreen.onRunStateChange(false)
        }
    }

    private fun getCustomSerialExamples(): List<CustomSerialExample> {
        val fields = R.raw::class.java.fields

        return fields
            .filter {
                it.name.startsWith("customserial_")
            }
            .mapNotNull { field ->
                try {
                    CustomSerialExample(
                        name = field.name
                            .removePrefix("customserial_")
                            .removeSuffix("_json")
                            .replace('_', ' '),
                        resourceId = field.getInt(null)
                    )
                } catch (_: Exception) {
                    null
                }
            }
            .sortedBy { it.name }
    }

    private fun setupCustomSerialScripts() {
        val spinner =
            findViewById<Spinner>(
                R.id.custom_serial_script_spinner
            )

        val examples =
            getCustomSerialExamples()

        val adapter =
            ArrayAdapter(
                context,
                android.R.layout.simple_spinner_item,
                examples.map { it.name }
            )

        adapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item
        )

        spinner.adapter = adapter

        val defaultIndex =
            examples.indexOfFirst {
                it.name == "elm327 basic"
            }

        if (defaultIndex >= 0) {
            spinner.setSelection(
                defaultIndex,
                false
            )
        }

        spinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {

                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    val example =
                        examples.getOrNull(position)
                            ?: return

                    loadCustomSerialExample(
                        example.resourceId
                    )
                }

                override fun onNothingSelected(
                    parent: AdapterView<*>?
                ) {
                }
            }
    }

    private fun loadCustomSerialExample(
        resourceId: Int
    ) {
        try {
            val text =
                resources
                    .openRawResource(resourceId)
                    .bufferedReader()
                    .use { it.readText() }

            val json =
                JSONObject(text)

            customSerialScreen.fromJson(json) { error ->
                activity.appendLog(
                    "Custom serial script: $error",
                    LogLevel.ERROR
                )
            }
        } catch (e: Exception) {
            activity.appendLog(
                "Cannot load custom serial script: ${e.message}",
                LogLevel.ERROR
            )
        }
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

        val content = JSONObject()
        desc.put("content", content)

        var content_ecus = JSONArray()
        content.put("ecu", content_ecus)
        for (ecu in ecus) {
            content_ecus.put(ecu.toJson())
        }
        content.put("ignition", libautodiag.getIgnitionState())

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

    override fun fromJson(desc: JSONObject, parseErrorHandler: ((String) -> Unit)?) {
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

        val content = desc.optJSONObject("content")
        if ( content == null ) {
            activity.appendLog(
                getString(R.string.sim_no_content, schemaVersion),
                LogLevel.ERROR
            )
            return
        }

        // reset current state
        ecuClear()

        val content_ecus = content.optJSONArray("ecu")
        if ( content_ecus != null ) {
            for (i in 0 until content_ecus.length()) {
                val ecuDesc = content_ecus.getJSONObject(i)
    
                val ecu = Ecu.createFromJSON(ecuDesc, activity)
                if ( ecu != null ) {
                    ecus.add(ecu)
                    addEcuRow(ecu)
                }
            }
        }
        val content_ignition = content.optInt("ignition")
        libautodiag.setIgnitionState(content_ignition)
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
        for (i in ecus.indices.reversed()) {
            val ecu = ecus[i]

            libautodiag.removeEcuByAddress(ecu.address)
            ecuListView.removeViewAt(i)
            ecus.removeAt(i)
        }
    }
    fun ecuRemoveByAddress(address: EcuAddress) {
        for (i in ecus.indices.reversed()) {
            val ecu = ecus[i]

            if (ecu.address == address) {
                libautodiag.removeEcuByAddress(ecu.address)
                ecuListView.removeViewAt(i)
                ecus.removeAt(i)
            }
        }
    }
}
