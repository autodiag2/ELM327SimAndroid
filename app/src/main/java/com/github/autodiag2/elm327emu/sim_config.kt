package com.github.autodiag2.elm327emu

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import java.io.File


data class CarConfigSummary(
    val file: File,
    val name: String,
    val ecuCount: Int
)
private class ConfigAdapter(
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
    private fun updateListState() {
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
        Toast.makeText(activity, getString(activity, R.string.sim_config_deleted), Toast.LENGTH_SHORT).show()
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

        Toast.makeText(activity, getString(activity, R.string.sim_config_exported), Toast.LENGTH_SHORT).show()
        updateListState()
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
class SimsConfig(
    activity: MainActivity,
    onConfigSelected: (File) -> Unit
) :ViewGroup(activity) {

    private val recycler: RecyclerView
    private val adapter: ConfigAdapter

    init {
        LayoutInflater.from(context).inflate(R.layout.sim_load_config, activity.contentFrame, true)


        recycler = findViewById(R.id.config_list)
        recycler.layoutManager = LinearLayoutManager(activity)

        val configs = mutableListOf<CarConfigSummary>()

        val adapter = ConfigAdapter(configs, activity,
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

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {

    }

}