package com.github.autodiag2.elm327emu.sim

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.cardview.widget.CardView
import com.github.autodiag2.elm327emu.log.LogLevel
import com.github.autodiag2.elm327emu.MainActivity
import com.github.autodiag2.elm327emu.R
import com.github.autodiag2.elm327emu.libautodiag
import com.github.autodiag2.elm327emu.sim.ecu.Ecu
import com.github.autodiag2.elm327emu.sim.ecu.EcuAddress
import com.github.autodiag2.elm327emu.sim.ecu.EcuType
import com.github.autodiag2.elm327emu.sim.ecu.EcuCitroenC5X7View
import com.github.autodiag2.elm327emu.sim.ecu.EcuCycleView
import com.github.autodiag2.elm327emu.sim.ecu.EcuGui
import com.github.autodiag2.elm327emu.sim.ecu.EcuGuiView
import com.github.autodiag2.elm327emu.sim.ecu.EcuRandomView
import com.github.autodiag2.elm327emu.sim.ecu.EcuReplayView
import com.github.autodiag2.elm327emu.sim.ecu.EcuScriptView
import com.github.autodiag2.elm327emu.ui.JsonConfigurable
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import com.github.autodiag2.elm327emu.IgnitionState
import android.content.Context

import com.github.autodiag2.elm327emu.sim.serial.CustomController
import com.github.autodiag2.elm327emu.sim.serial.CustomViewContainer
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelChildren
import android.widget.EditText
import java.util.Locale
import android.content.res.ColorStateList
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.github.autodiag2.elm327emu.EmulatorForegroundService

class Sim(
    private val service: EmulatorForegroundService
) : JsonConfigurable, CustomController.Listener {

    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    public val customSerialScreen: CustomController
    val ecus = mutableListOf<Ecu>()
    var running: Boolean = false
    public var view: SimView? = null

    companion object {
        const val SCHEMA: String = "autodiag/sim/any"
        const val SCHEMA_VERSION: Double = 1.0
    }

    public data class CustomSerialExample(
        val name: String,
        val resourceId: Int? = null,
        val file: File? = null
    )

    init {
        customSerialScreen = CustomController(service, listener = this)
        buildAddECU(Ecu.DEFAULT_ADDRESS, getString(R.string.sim_ecu_gui_ecu_name), EcuType.gui)
        customSerialScreen.setReplayEntriesProvider {
            service.log.model.buffer
        }
    }

    fun detachView() {
        this.view = null
        for(ecu in ecus) {
            ecu.detachView()
        }
        customSerialScreen.detachView()
    }

    fun attachView(view: SimView?) {
        this.view = view
        if ( view != null ) {
            view.onToggleStart = ::onToggleStart
            view.onStart = ::onStart
            view.onStop = ::onStop
            view.isRunning = ::isRunning
            view.loadCustomSerialExample = ::loadCustomSerialExample
            view.onAddEcu = ::buildAddECU
            view.getCustomSerialExamples = ::getCustomSerialExamples
            view.onEcuRemoveByAddress = ::ecuRemoveByAddress
            view.onShowConnectedClients = ::onShowConnectedClients
            view.onScriptOpen = ::onScriptOpen
            view.getJson = {
                customSerialScreen
                    .toJson()
                    .toString(2)
            }
            view.post {
                view.ecuListView.removeAllViews()
                for(ecu in ecus) {
                    createAttachEcuView(ecu, view.context)
                    view.addEcuRow(ecu)
                }
                view.uiSetRunState(isRunning())
                view.setupCustomSerialScripts()
            }
            view.customViewContainer.customView.model = customSerialScreen
            customSerialScreen.attachView(view.customViewContainer.customView)
            customSerialOnRunStateChange(customSerialScreen.isRunning())
        }
    }

    fun onShowConnectedClients() {
        val view = this.view
        if ( view == null ) {
            return
        }
        view.showConnectedClients(service.clients)
    }

    fun createAttachEcuView(ecu: Ecu, context: Context) {
        val ecuView = when(ecu.type) {
            EcuType.citroen_c5_x7 -> EcuCitroenC5X7View(context)
            EcuType.cycle -> EcuCycleView(context)
            EcuType.gui -> {
                val ecu = ecu as EcuGui
                EcuGuiView(context, ecu.allSignals.toTypedArray())
            }
            EcuType.random -> EcuRandomView(context)
            EcuType.replay -> EcuReplayView(context)
            EcuType.bytearray -> EcuScriptView(context)
        }
        ecu.attachView(ecuView)
    }

    fun stop() {
        running = false
        service.stopEmulator()
        scope.coroutineContext.cancelChildren()
        appendLog(getString(R.string.log_main_bluetooth_server_stopped), LogLevel.INFO)
    }

    fun start() {
        running = true
        service.startEmulator()
    }

    fun appendLog(text: String, level: LogLevel = LogLevel.DEBUG) {
        service.log.append(text, level)
    }

    fun onScriptOpen() {
        val view = this.view
        if ( view != null ) {
            view.doOnScriptOpen(customSerialScreen.view!!.parentView)
        }
    }

    fun onToggleStart() {
        customSerialScreen.toggleStart()
    }

    fun onStart() {
        start()
    }

    fun onStop() {
        stop()
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

    private fun getCustomSerialExamples(): List<CustomSerialExample> {
        val examples = mutableListOf<CustomSerialExample>()

        val fields = R.raw::class.java.fields

        fields
            .filter {
                it.name.startsWith("customserial_")
            }
            .forEach { field ->
                try {
                    examples.add(
                        CustomSerialExample(
                            name = field.name
                                .removePrefix("customserial_")
                                .removeSuffix("_json")
                                .replace('_', ' '),
                            resourceId = field.getInt(null)
                        )
                    )
                } catch (_: Exception) {
                }
            }

        val scriptDir =
            File(
                service.filesDir,
                "sim/elm327/serialscript"
            )

        if (scriptDir.isDirectory) {
            scriptDir
                .listFiles { file ->
                    file.isFile &&
                        file.extension.equals(
                            "json",
                            ignoreCase = true
                        )
                }
                ?.forEach { file ->
                    examples.add(
                        CustomSerialExample(
                            name = file.nameWithoutExtension
                                .replace('_', ' '),
                            file = file
                        )
                    )
                }
        }

        return examples.sortedBy {
            it.name.lowercase(Locale.ROOT)
        }
    }

    private fun loadCustomSerialExample(
        example: CustomSerialExample
    ) {
        try {
            val text =
                if (example.resourceId != null) {
                    service.resources
                        .openRawResource(example.resourceId)
                        .bufferedReader()
                        .use { it.readText() }
                } else {
                    example.file
                        ?.readText()
                        ?: return
                }

            val json =
                JSONObject(text)

            customSerialScreen.fromJson(json) { error ->
                appendLog(
                    "Custom serial script: $error",
                    LogLevel.ERROR
                )
            }
        } catch (e: Exception) {
            appendLog(
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
            appendLog(
                getString(R.string.sim_invalid_ecu_schema, schema),
                LogLevel.ERROR
            )
            return
        }

        val schemaVersion = desc.optDouble("version")
        if ( schemaVersion != SCHEMA_VERSION ) {
            appendLog(
                getString(R.string.sim_unsupported_ecu_schema_version, schemaVersion),
                LogLevel.ERROR
            )
            return
        }

        val content = desc.optJSONObject("content")
        if ( content == null ) {
            appendLog(
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
    
                val ecu = Ecu.createFromJSON(ecuDesc, service)
                if ( ecu != null ) {
                    ecus.add(ecu)
                    val view: SimView? = this.view
                    if ( view != null ) {
                        createAttachEcuView(ecu, view.context)
                        view.addEcuRow(ecu)
                    }
                }
            }
        }
        val content_ignition = content.optInt("ignition")
        libautodiag.setIgnitionState(content_ignition)
    }

    fun getString(resId: Int, vararg formatArgs: Any?): String {
        return service.getString(resId, *formatArgs.map { it ?: "" }.toTypedArray())
    }

    private fun buildAddECU(address: EcuAddress, name: String, type: EcuType): Unit {

        ecuRemoveByAddress(address)

        val ecu = Ecu.create(type, service, address, name)

        ecus.add(ecu)
        val view: SimView? = this.view
        if ( view != null ) {
            createAttachEcuView(ecu, view.context)
            view.addEcuRow(ecu)
        }
    }

    fun ecuClear() {
        for (i in ecus.indices.reversed()) {
            val ecu = ecus[i]

            libautodiag.removeEcuByAddress(ecu.address)
            val view = this.view
            if ( view != null ) {
                view.ecuListView.removeViewAt(i)
            }
            ecus.removeAt(i)
        }
    }
    fun ecuRemoveByAddress(address: EcuAddress) {
        for (i in ecus.indices.reversed()) {
            val ecu = ecus[i]

            if (ecu.address == address) {
                libautodiag.removeEcuByAddress(ecu.address)
                val view = this.view
                if ( view != null ) {
                    view.post {
                        view.ecuListView.removeViewAt(i)
                    }
                }
                ecus.removeAt(i)
            }
        }
    }
    override fun customSerialOnRunStateChange(newState: Boolean) {
        val view = this.view
        if ( view != null ) {
            view.onRunStateChanged(newState)
        }
    }
}
