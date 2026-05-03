package com.github.autodiag2.elm327emu

import android.content.Intent
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class EcuType(val label: String) {
    GUI("GUI"),
    SCRIPT("Script");

    override fun toString() = label
}

data class CarConfigSummary(
    val file: File,
    val name: String,
    val ecuCount: Int
)
class ConfigAdapter(
    private val items: MutableList<CarConfigSummary>,
    private val activity: MainActivity,
    private val onClick: (CarConfigSummary) -> Unit,
) : RecyclerView.Adapter<ConfigAdapter.VH>() {

    private val selectedItems = mutableSetOf<CarConfigSummary>()

    fun onOpenSimConfig() {
        val config = selectedItems.firstOrNull() ?: return
        return onClick(config)
    }

    fun shareConfigAsText() {
        val config = selectedItems.firstOrNull() ?: return
        val text = config.file.readText()

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, config.name)
            putExtra(Intent.EXTRA_TEXT, text)
        }

        activity.startActivity(
            Intent.createChooser(intent, "Share config")
        )
    }

    fun exportConfigToFile(config: CarConfigSummary) {
        activity.pendingExportConfig = config

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, config.file.name)
        }

        activity.exportLauncher.launch(intent)
    }

    fun onExportFile() {
        val config = selectedItems.firstOrNull() ?: return
        exportConfigToFile(config)
        selectedItems.clear()
        refresh()
    }

    fun onDelete() {
        for(config in selectedItems) {
            config.file.delete()
        }
        Toast.makeText(activity, getString(activity, R.string.sim_config_deleted), Toast.LENGTH_SHORT).show()
        selectedItems.clear()
        refresh()
    }
    fun onExport() {
        val config = selectedItems.firstOrNull() ?: return
        val text = config.file.readText()

        val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager

        val clip = android.content.ClipData.newPlainText(config.name, text)
        clipboard.setPrimaryClip(clip)

        Toast.makeText(activity, getString(activity, R.string.sim_config_exported), Toast.LENGTH_SHORT).show()
        selectedItems.clear()
        refresh()
    }
    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.config_name)
        val ecus: TextView = view.findViewById(R.id.config_ecu_count)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.sim_load_config_item, parent, false)
        return VH(view)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]

        holder.name.text = item.name
        holder.ecus.text = "ECUs: ${item.ecuCount}"

        holder.itemView.setOnClickListener {
            if (selectedItems.isNotEmpty()) {
                toggleSelection(item, holder)
            } else {
                onClick(item)
            }
        }

        holder.itemView.setOnLongClickListener {
            toggleSelection(item, holder)
            true
        }
    }
    private fun toggleSelection(item: CarConfigSummary, holder: VH) {
        if (selectedItems.contains(item)) {
            selectedItems.remove(item)
            holder.itemView.isActivated = false
            holder.itemView.alpha = 1f
        } else {
            selectedItems.add(item)
            holder.itemView.isActivated = true
            holder.itemView.alpha = 0.6f
        }
    }
    fun refresh() {
        val configs = items
        items.clear()

        val dir = File(activity.filesDir, "config")
        if (!dir.exists()) dir.mkdirs()

        val files = dir.listFiles { f -> f.extension == "json" }
            ?.sortedByDescending { it.lastModified() } // latest first

        files?.forEach { file ->
            try {
                val text = file.readText()
                val json = JSONArray(text)

                val ecuCount = json.length()
                val name = file.nameWithoutExtension

                configs.add(
                    CarConfigSummary(
                        file = file,
                        name = name,
                        ecuCount = ecuCount
                    )
                )

            } catch (e: Exception) {
                // visible feedback instead of silent failure
                e.printStackTrace()
                Toast.makeText(
                    activity,
                    getString(activity, R.string.sim_load_config_error_invalid, file.name),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        notifyDataSetChanged()
    }
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
    // sims screen
    lateinit var recycler: RecyclerView

    init {
        LayoutInflater.from(context).inflate(R.layout.sim_main, this, true)
        setupSimView(this)
        buildAddECUToGUI(0xE8, getString(R.string.sim_main_ecu_config_gui_ecu_name), EcuType.GUI)
    }

    fun buildLoadConfigView(onConfigSelected: (File) -> Unit): View {

        val view = activity.layoutInflater.inflate(
            R.layout.sim_load_config,
            activity.contentFrame,
            false
        )

        recycler = view.findViewById(R.id.config_list)
        recycler.layoutManager = LinearLayoutManager(activity)

        val configs = mutableListOf<CarConfigSummary>()

        val adapter = ConfigAdapter(configs, activity,
            onClick = { config ->
                onConfigSelected(config.file)
            }
        )

        recycler.adapter = adapter

        adapter.refresh()

        return view
    }

    fun saveConfig(path: String) {
        File(path).writeText(saveConfigAsJson())
    }

    fun saveConfigAsJson(): String {
        val root = JSONArray()

        for (ecu in ecus) {
            val obj = JSONObject()

            obj.put("id", ecu.id)
            obj.put("name", ecu.name)
            obj.put("type", ecu.type.name)

            when (ecu.type) {
                EcuType.GUI -> {
                    // GUI state extraction from SimGeneratorGui (global state)
                    val gui = JSONObject()
                    val ecuGuiView = ecu.screen as EcuGuiView

                    gui.put("ecuName", SimGeneratorGui.ecuName)
                    gui.put("vin", SimGeneratorGui.vin)
                    gui.put("mil", SimGeneratorGui.mil)
                    gui.put("dtcCleared", SimGeneratorGui.dtcCleared)

                    val dtcs = JSONArray()
                    SimGeneratorGui.dtcs.forEach { dtcs.put(it) }
                    gui.put("dtcs", dtcs)

                    // signals
                    val signals = JSONArray()
                    ecuGuiView.addedSignalPaths.forEach { path ->
                        val value = libautodiag.getSignalValue(path)
                        if (!value.isNaN()) {
                            val s = JSONObject()
                            s.put("path", path)
                            s.put("value", value)
                            signals.put(s)
                        }
                    }

                    gui.put("signals", signals)
                    obj.put("gui", gui)
                }

                EcuType.SCRIPT -> {
                    obj.put("script", getScript(ecu))
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
        SimGeneratorGui.dtcs.clear()
        SimGeneratorGui.mil = false
        SimGeneratorGui.ecuName = ""
        SimGeneratorGui.vin = ""

        for (i in 0 until root.length()) {
            val obj = root.getJSONObject(i)

            val id = obj.getInt("id")
            val name = obj.getString("name")
            val type = EcuType.valueOf(obj.getString("type"))

            val ecu = buildEcuConfig(id, name, type)
            ecus.add(ecu)
            addEcuRow(ecu)

            when (type) {
                EcuType.GUI -> {
                    val gui = obj.optJSONObject("gui") ?: continue

                    val ecuGuiView: EcuGuiView = ecu.screen as EcuGuiView
                    val ecu_name = ecuGuiView.findViewById<EditText>(R.id.ecu_name)
                    ecu_name.setText(gui.optString("ecuName", ""))
                    val vin = ecuGuiView.findViewById<EditText>(R.id.vin)
                    vin.setText(gui.optString("vin", ""))
                    val mil = ecuGuiView.findViewById<CheckBox>(R.id.mil_state)
                    mil.isChecked = gui.optBoolean("mil", false)
                    val dtcCleared = ecuGuiView.findViewById<CheckBox>(R.id.dtcs_cleared)
                    dtcCleared.isChecked = gui.optBoolean("dtcCleared", false)

                    // dtcs
                    ecuGuiView.clearDTCs()
                    val dtcs = gui.optJSONArray("dtcs")
                    if (dtcs != null) {
                        for (j in 0 until dtcs.length()) {
                            ecuGuiView.addDtcByCode(dtcs.getString(j))
                        }
                    }

                    // signals
                    ecuGuiView.clearSignals()
                    val signals = gui.optJSONArray("signals")
                    if (signals != null) {
                        for (j in 0 until signals.length()) {
                            val s = signals.getJSONObject(j)
                            val path = s.getString("path")
                            val value = s.getDouble("value")

                            ecuGuiView.addSignalByPath(path)
                            ecuGuiView.setSignalValue(path, value)
                        }
                    }
                }

                EcuType.SCRIPT -> {
                    val script = obj.optString("script", "")
                    val editor = ecu.screen.findViewById<EditText>(R.id.lua_editor)
                    updateScript(script, ecu)
                    editor.setText(script)
                }
            }
        }
    }

    fun buildEcuConfig(address: Int, name: String, type: EcuType): EcuConfig {
        return when ( type ) {
            EcuType.GUI -> buildEcuGuiConfig(address, name, activity)
            EcuType.SCRIPT -> buildSimScriptView(address, name, activity)
        }
    }

    fun getString(resId: Int, vararg formatArgs: Any?): String {
        return activity.getString(resId, *formatArgs.map { it ?: "" }.toTypedArray())
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
                Toast.makeText(activity, getString(R.string.sim_main_ecu_config_invalid_hex_id), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            buildAddECUToGUI(address, getString(R.string.sim_main_ecu_config_ecu_name, type), type)
        }

        var running = false
        view.findViewById<Button>(R.id.sim_state).apply {
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
    fun ecuClear() {
        // iterate backwards to safely remove
        val offset_in_layout = 1
        for (i in ecus.indices.reversed()) {
            val ecu = ecus[i]

            libautodiag.removeEcuByAddress(ecu.id.toByte())
            ecuListView.removeViewAt(i + offset_in_layout)
            ecus.removeAt(i)
        }
    }
    fun ecuRemoveByAddress(address: Int) {
        // iterate backwards to safely remove
        val offset_in_layout = 1
        for (i in ecus.indices.reversed()) {
            val ecu = ecus[i]

            if (ecu.id.toByte() == address.toByte()) {
                libautodiag.removeEcuByAddress(ecu.id.toByte())
                ecuListView.removeViewAt(i + offset_in_layout)
                ecus.removeAt(i)
            }
        }
    }
}
