package com.github.autodiag2.elm327emu.sim

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.cardview.widget.CardView
import java.io.File
import com.github.autodiag2.elm327emu.LogLevel
import com.github.autodiag2.elm327emu.MainActivity
import com.github.autodiag2.elm327emu.R
import com.github.autodiag2.elm327emu.libautodiag
import com.github.autodiag2.elm327emu.sim.ecu.Ecu
import com.github.autodiag2.elm327emu.sim.ecu.EcuAddress
import com.github.autodiag2.elm327emu.sim.ecu.EcuType
import com.github.autodiag2.elm327emu.ui.JsonConfigurable
import com.github.autodiag2.elm327emu.sim.Sim.CustomSerialExample
import com.github.autodiag2.elm327emu.IgnitionState
import android.widget.FrameLayout
import com.github.autodiag2.elm327emu.com.Bridge
import com.github.autodiag2.elm327emu.sim.serial.CustomView

class SimView(private val context: Context): FrameLayout(context) {

    public val ecuListView: ViewGroup
    private val ecuAddSelect: Spinner
    private lateinit var connectedClientsButton: Button
    private lateinit var customSerialScriptSpinner: Spinner
    private lateinit var customSerialScriptAdapter: ArrayAdapter<String>
    private var customSerialExamples = emptyList<CustomSerialExample>()
    private fun selectedType(): EcuType = EcuType.entries[ecuAddSelect.selectedItemPosition]
    private val activity: MainActivity

    // -- Controller events --
    public lateinit var onAddEcu: ((address: EcuAddress, name: String, type: EcuType) -> Unit)
    public lateinit var getCustomSerialExamples: (() -> List<CustomSerialExample>)
    public lateinit var loadCustomSerialExample: ((example: CustomSerialExample) -> Unit)
    public lateinit var onToggleStart: (() -> Unit)
    public lateinit var onScriptOpen: (() -> Unit)
    public lateinit var isRunning: (() -> Boolean)
    public lateinit var getJson: (() -> String)
    public lateinit var onStop: (() -> Unit)
    public lateinit var onStart: (() -> Unit)
    public lateinit var onEcuRemoveByAddress: ((address: EcuAddress) -> Unit)
    // -- End Controller events --

    init {
        this.activity = context as MainActivity
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
                toast(getString(R.string.sim_ecu_config_invalid_hex_id))
                return@setOnClickListener
            }

            onAddEcu.invoke(address.toByte(), getString(R.string.sim_ecu_config_ecu_name, getString(type.label_id)), type)
        }

        connectedClientsButton =
            findViewById(R.id.sim_connected_clients)

        connectedClientsButton.setOnClickListener {
            showConnectedClients()
        }
        updateConnectedClientsButton()

        findViewById<Button>(R.id.sim_custom_serial_script_open).setOnClickListener {
            onScriptOpen.invoke()
        }
        findViewById<Button>(
            R.id.sim_custom_serial_script_save
        ).setOnClickListener {
            showSaveCustomSerialDialog(getJson.invoke())
        }
        
        findViewById<Button>(R.id.sim_state).apply {
            setOnClickListener {
                if (activity.isPermissionsGranted()) {
                    text = if (isRunning.invoke()) {
                        getString(R.string.sim_stop_sim)
                    } else {
                        getString(R.string.sim_start_sim)
                    }

                    val colorAttr =
                        if (isRunning.invoke()) {
                            R.attr.colorAccentInProgress
                        } else {
                            R.attr.colorAccentSuccess
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

                    if (isRunning.invoke()) {
                        onStop.invoke()
                    } else {
                        onStart.invoke()
                    }
                } else {
                    activity.requestPermissions()
                }
            }
        }
        findViewById<ImageButton>(
            R.id.sim_custom_serial_script_play_pause
        ).setOnClickListener {
            onToggleStart.invoke()
        }
        findViewById<ToggleButton>(R.id.ignition_state).apply {
            val attributes = context.theme.obtainStyledAttributes(
                intArrayOf(
                    R.attr.colorAccentSuccess,
                    R.attr.colorAccentInProgress
                )
            )
            val colorSuccess = attributes.getColor(
                0,
                0xff00ff00.toInt()
            )
            val colorInProgress = attributes.getColor(
                1,
                0xff009688.toInt()
            )
            attributes.recycle()
            backgroundTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf()
                ),
                intArrayOf(
                    colorSuccess,
                    colorInProgress
                )
            )

            isChecked = libautodiag.getIgnitionStateAs() == IgnitionState.ON

            setOnCheckedChangeListener { _, isChecked ->
                libautodiag.setIgnitionState(
                    if (isChecked) IgnitionState.ON else IgnitionState.OFF
                )
            }
        }
    }
    fun onRunStateChanged(newState: Boolean) {
        Handler(Looper.getMainLooper()).post {
            val button =
                findViewById<ImageButton>(
                    R.id.sim_custom_serial_script_play_pause
                )
    
            if (newState) {
                button.setImageResource(R.drawable.ic_pause)
                button.contentDescription =
                    getString(R.string.sim_custom_serial_script_pause)
            } else {
                button.setImageResource(R.drawable.ic_play)
                button.contentDescription =
                    getString(R.string.sim_custom_serial_script_play)
            }
        }
    }

    fun getString(resId: Int, vararg formatArgs: Any?): String {
        return context.getString(resId, *formatArgs.map { it ?: "" }.toTypedArray())
    }
    
    fun doOnScriptOpen(view: CustomView?) {
        if ( view != null) {
            activity.showNestedScreen(view)
        }
    }

    private fun showSaveCustomSerialDialog(json: String) {
        val currentName =
            customSerialExamples
                .getOrNull(customSerialScriptSpinner.selectedItemPosition)
                ?.name
                ?: ""

        val input =
            EditText(context).apply {
                hint = getString(
                    R.string.sim_custom_serial_script_save_name
                )
                setText(currentName)
                setSelection(text.length)
                inputType =
                    android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }

        assert(context is MainActivity)
        AlertDialog.Builder(context)
            .setTitle(
                R.string.sim_custom_serial_script_save
            )
            .setView(input)
            .setPositiveButton(
                android.R.string.ok
            ) { _, _ ->
                saveCustomSerialScript(
                    input.text.toString(),
                    json
                )
            }
            .setNegativeButton(
                android.R.string.cancel,
                null
            )
            .show()
    }

    private fun toast(text: Any) {
        Handler(Looper.getMainLooper()).post {
            val message = when (text) {
                is String -> text
                is Int -> context.getString(text)
                else -> return@post
            }

            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun saveCustomSerialScript(
        name: String,
        json: String
    ) {
        var filename =
            name.trim()
                .replace(' ', '_')

        if (filename.isEmpty()) {
            toast(R.string.sim_custom_serial_script_save_invalid_name)
            return
        }

        if (!filename.endsWith(".json", ignoreCase = true)) {
            filename += ".json"
        }

        val directory =
            File(
                activity.filesDir,
                "sim/elm327/serialscript"
            )

        if (!directory.exists() &&
            !directory.mkdirs()
        ) {
            toast(R.string.sim_custom_serial_script_save_error)
            return
        }

        try {
            val file =
                File(
                    directory,
                    filename
                )

            file.writeText(
                json
            )

            toast(
                getString(
                    R.string.sim_custom_serial_script_save_success,
                    filename
                )
            )

            val savedExample =
                CustomSerialExample(
                    name = file.nameWithoutExtension
                        .replace('_', ' '),
                    file = file
                )

            refreshCustomSerialScripts(
                preferredExample = savedExample
            )

        } catch (e: Exception) {

            toast(getString(
                R.string.sim_custom_serial_script_save_error_detail,
                e.message ?: ""
            ))
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

    public fun setupCustomSerialScripts() {
        customSerialScriptSpinner =
            findViewById(
                R.id.custom_serial_script_spinner
            )

        customSerialScriptAdapter =
            ArrayAdapter(
                context,
                android.R.layout.simple_spinner_item,
                mutableListOf()
            )

        customSerialScriptAdapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item
        )

        customSerialScriptSpinner.adapter =
            customSerialScriptAdapter

        customSerialScriptSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {

                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    val example =
                        customSerialExamples.getOrNull(position)
                            ?: return

                    loadCustomSerialExample.invoke(
                        example
                    )
                }

                override fun onNothingSelected(
                    parent: AdapterView<*>?
                ) {
                }
            }

        refreshCustomSerialScripts(
            preferredExample = null
        )
    }

    private fun refreshCustomSerialScripts(
        preferredExample: CustomSerialExample?
    ) {
        customSerialExamples = getCustomSerialExamples.invoke()

        customSerialScriptAdapter.clear()

        customSerialScriptAdapter.addAll(
            customSerialExamples.map {
                it.name
            }
        )

        customSerialScriptAdapter.notifyDataSetChanged()

        val selectedIndex =
            if (preferredExample != null) {
                customSerialExamples.indexOfFirst {
                    it.resourceId == preferredExample.resourceId &&
                        it.file?.absolutePath ==
                        preferredExample.file?.absolutePath
                }
            } else {
                customSerialExamples.indexOfFirst {
                    it.name == "elm327 basic"
                }
            }

        if (selectedIndex >= 0) {
            customSerialScriptSpinner.setSelection(
                selectedIndex,
                false
            )
        }
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
                        onEcuRemoveByAddress.invoke(ecu.address)
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
}