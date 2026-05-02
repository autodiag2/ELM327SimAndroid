package com.github.autodiag2.elm327emu

import android.view.LayoutInflater
import android.view.View
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

enum class EcuType(val label: String) {
    GUI("GUI"),
    SCRIPT("Data Script");

    override fun toString() = label
}
data class EcuConfig(
    val id: Int,
    var name: String,
    var type: EcuType,
    var screen: View
)
class SimView(
    private val activity: MainActivity
) : FrameLayout(activity) {

    lateinit var ecuListView: ViewGroup
    val ecus = mutableListOf<EcuConfig>()
    lateinit var ecuAddSelect: Spinner

    init {
        LayoutInflater.from(context).inflate(R.layout.sim_main, this, true)
        setupSimView(this)
        buildAddECUToGUI(0xE8, "from GUI", EcuType.GUI)
    }

    fun buildEcuConfig(address: Int, name: String, type: EcuType): EcuConfig {
        return when ( type ) {
            EcuType.GUI -> buildEcuGuiConfig(address, name, activity)
            EcuType.SCRIPT -> buildSimScriptView(address, name, activity)
        }
    }

    private fun buildAddECUToGUI(address: Int, name: String, type: EcuType) {

        ecuRemoveByAddress(address)

        val ecu = buildEcuConfig(address, name, type)

        ecus.add(ecu)
        addEcuRow(ecu)
    }

    private fun setupSimView(view: View) {
        ecuListView = view.findViewById<ViewGroup>(R.id.ecu_list)
        val addEcuBtn = view.findViewById<Button>(R.id.add_ecu)
        val ecuIdInput = view.findViewById<EditText>(R.id.ecu_id_input)
        ecuAddSelect = view.findViewById(R.id.ecu_type_spinner)
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
                Toast.makeText(activity, "Invalid hex ID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            buildAddECUToGUI(address, "from ${type}", type)
        }

        var running = false
        view.findViewById<Button>(R.id.sim_state).apply {
            setOnClickListener {
                if (activity.isPermissionsGranted()) {
                    running = !running
                    text = if (running) "Stop Sim" else "Start Sim"
                    if (running) activity.startServer() else activity.stopServer()
                } else {
                    activity.requestPermissions()
                }
            }
        }
    }

    fun addEcuRow(ecu: EcuConfig) {
        val row = activity.layoutInflater.inflate(R.layout.sim_main_ecu_row, ecuListView, false)
        val cardView = row.findViewById<CardView>(R.id.ecu_row_cardview)
        val title = row.findViewById<TextView>(R.id.ecu_title)

        title.text = "ECU 0x${ecu.id.toString(16).uppercase()} (${ecu.name})"

        row.setOnClickListener {
            activity.openEcuConfig(ecu)
        }

        cardView.setOnLongClickListener {

            val popup = PopupMenu(activity, cardView)
            popup.menuInflater.inflate(R.menu.main_ecu_row, popup.menu)

            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_delete -> {
                        ecuRemoveByAddress(ecu.id)
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

    private fun ecuRemoveByAddress(address: Int) {
        // iterate backwards to safely remove
        val offset_in_layout = 1
        for (i in ecus.indices.reversed()) {
            val ecu = ecus[i]

            if (ecu.id.toByte() == address.toByte()) {
                ecuListView.removeViewAt(i + offset_in_layout)
                ecus.removeAt(i)
            }
        }
    }
}
