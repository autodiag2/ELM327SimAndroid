package com.github.autodiag2.elm327emu.sim

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.autodiag2.elm327emu.LogLevel
import com.github.autodiag2.elm327emu.MainActivity
import com.github.autodiag2.elm327emu.R
import com.github.autodiag2.elm327emu.sim.Sim.Companion.SCHEMA
import com.github.autodiag2.elm327emu.sim.Sim.Companion.SCHEMA_VERSION
import com.github.autodiag2.elm327emu.sim.ecu.getString
import com.github.autodiag2.elm327emu.ui.NestedScreen
import org.json.JSONObject
import java.io.File


data class SimSummary(
    val file: File,
    val name: String,
    val ecuCount: Int
)
private class SimListAdapter(
    private val items: MutableList<SimSummary>,
    private val activity: MainActivity,
    private val onClick: (SimSummary) -> Unit,
) : RecyclerView.Adapter<SimListAdapter.VH>() {

    private val selectedItems = mutableSetOf<SimSummary>()

    fun onOpenSimConfig() {
        val config = selectedItems.firstOrNull() ?: return
        updateListState()
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
        updateListState()
    }

    fun exportConfigToFile(config: SimSummary) {
        activity.pendingExportConfig = config

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, config.file.name)
        }

        updateListState()
        activity.exportLauncher.launch(intent)
    }
    fun updateListState() {
        selectedItems.clear()
        notifyDataSetChanged()
    }

    fun onExportFile() {
        val config = selectedItems.firstOrNull() ?: return
        exportConfigToFile(config)
        updateListState()
    }

    fun onDelete() {
        if ( selectedItems.firstOrNull() == null ) {
            return
        }
        for(config in selectedItems) {
            config.file.delete()
        }
        Toast.makeText(activity, getString(activity, R.string.sim_list_deleted), Toast.LENGTH_SHORT).show()
        updateListState()
        refresh()
    }
    fun onExport() {
        val config = selectedItems.firstOrNull() ?: return
        val text = config.file.readText()

        val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager

        val clip = android.content.ClipData.newPlainText(config.name, text)
        clipboard.setPrimaryClip(clip)

        Toast.makeText(activity, getString(activity, R.string.sim_list_exported), Toast.LENGTH_SHORT).show()
        updateListState()
    }
    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.config_name)
        val ecus: TextView = view.findViewById(R.id.config_ecu_count)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.sim_list_item, parent, false)
        return VH(view)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]

        holder.name.text = item.name
        holder.ecus.text = "ECUs: ${item.ecuCount}"

        val isSelected = selectedItems.contains(item)

        holder.itemView.isActivated = isSelected
        holder.itemView.alpha = if (isSelected) 0.6f else 1f

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
    private fun toggleSelection(item: SimSummary, holder: VH) {
        if (selectedItems.contains(item)) {
            selectedItems.remove(item)
            holder.itemView.isActivated = false
            holder.itemView.alpha = 1f
            activity.isSimItemSelectedMode = false
        } else {
            selectedItems.add(item)
            holder.itemView.isActivated = true
            holder.itemView.alpha = 0.6f
            activity.isSimItemSelectedMode = true
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
                val desc = JSONObject(text)
                val schema = desc.optString("schema")

                if(schema.isEmpty() || !schema.startsWith(SCHEMA)) {
                    activity.appendLog(
                        getString(activity, R.string.sim_invalid_ecu_schema, schema),
                        LogLevel.ERROR
                    )
                    return
                }

                val schemaVersion = desc.optDouble("version")
                if ( schemaVersion != SCHEMA_VERSION ) {
                    activity.appendLog(
                        getString(activity,
                            R.string.sim_unsupported_ecu_schema_version, schemaVersion),
                        LogLevel.ERROR
                    )
                    return
                }

                val content = desc.optJSONArray("content")
                if ( content == null ) {
                    activity.appendLog(
                        getString(activity, R.string.sim_no_content, schemaVersion),
                        LogLevel.ERROR
                    )
                    return
                }

                val ecuCount = content.length()
                val name = file.nameWithoutExtension

                configs.add(
                    SimSummary(
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
                    getString(activity, R.string.sim_list_error_invalid, file.name),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        notifyDataSetChanged()
    }
}
class SimList(
    activity: MainActivity,
    onConfigSelected: (File) -> Unit
) :LinearLayout(activity), NestedScreen {

    private val recycler: RecyclerView
    private val adapter: SimListAdapter

    init {
        LayoutInflater.from(context).inflate(R.layout.sim_list, this, true)

        recycler = findViewById(R.id.config_list)
        recycler.layoutManager = LinearLayoutManager(activity)

        val configs = mutableListOf<SimSummary>()

        val adapter = SimListAdapter(configs, activity,
            onClick = { config ->
                onConfigSelected(config.file)
            }
        )
        this.adapter = adapter
        recycler.adapter = adapter

        adapter.refresh()
    }

    fun onExportFile() {
        adapter.onExportFile()
    }

    fun onDelete() {
        adapter.onDelete()
    }

    fun onExport() {
        adapter.onExport()
    }

    fun onOpenSimConfig() {
        adapter.onOpenSimConfig()
    }

    fun shareConfigAsText() {
        adapter.shareConfigAsText()
    }

    override fun onBack() {
        adapter.updateListState()
    }

}