package com.github.autodiag2.elm327emu

import android.Manifest
import androidx.appcompat.app.AppCompatActivity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import kotlinx.coroutines.*
import android.bluetooth.BluetoothAdapter
import android.view.Gravity
import android.widget.FrameLayout
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import android.view.MenuItem
import android.content.Intent
import androidx.appcompat.widget.Toolbar
import android.content.Context
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Button
import android.widget.CheckBox
import androidx.core.widget.addTextChangedListener
import android.view.View
import android.widget.*
import android.bluetooth.BluetoothManager
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import org.luaj.vm2.*
import org.luaj.vm2.lib.jse.*

private const val REQUEST_CODE = 1

enum class EcuType(val label: String) {
    GUI("GUI"),
    SCRIPT("Data Script");

    override fun toString() = label
}
interface EcuByteArrayHandler {
    fun response(request: ByteArray): ByteArray
}
data class EcuConfig(
    val id: Int,
    var name: String,
    var type: EcuType,
    var screen: View
)
class LuaJEcuHandler(script: String) : EcuByteArrayHandler {

    private var globals = JsePlatform.standardGlobals()
    private val chunk = globals.load(script)

    init {
        chunk.call()
    }

    fun reload(script: String) {
        globals = JsePlatform.standardGlobals()
        globals.load(script).call()
    }

    override fun response(request: ByteArray): ByteArray {

        val luaReq = LuaTable()

        for (i in request.indices) {
            luaReq.set(i + 1, LuaValue.valueOf(request[i].toInt() and 0xFF))
        }

        val func = globals.get("response")

        val result = func.call(luaReq)

        val len = result.length()

        return ByteArray(len) { i ->
            result.get(i + 1).toint().toByte()
        }
    }
}
class MainActivity : AppCompatActivity() {
    private lateinit var btAdapter: BluetoothAdapter

    private val enableBtLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    // Order in the settings screen
    private val NETWORK_BT = 0
    private val NETWORK_BLE = 1
    private val NETWORK_IP = 2

    lateinit var bleBridge: BLEBridge
    lateinit var btBridge: BluetoothBridge
    lateinit var ntBridge: NetworkBridge

    public val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    lateinit var contentFrame: FrameLayout
    private lateinit var drawer: DrawerLayout

    lateinit var simView: View
    private lateinit var dtcClearedCheck: CheckBox
    lateinit var ecuListView: ViewGroup
    val ecus = mutableListOf<EcuConfig>()
    lateinit var ecuAddSelect: Spinner

    lateinit var settingsView: View
    lateinit var logView: LogView
    lateinit var statsView: StatsView

    private val screenStack = ArrayDeque<View>()

    public val prefs by lazy { getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }

    val saveLogLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val uri = result.data?.data ?: return@registerForActivityResult
                lifecycleScope.launch(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        val text = logRepo.snapshotUnsafe()
                            .joinToString("\n") { it.text }
                        out.write(text.toByteArray())
                    }
                }
            }
        }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            drawer.openDrawer(Gravity.LEFT)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun isPermissionsGranted(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) &&
                (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED)
        } else {
            return true
        }
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE), REQUEST_CODE)
        }
    }

    fun buildEcuGuiConfig(address: Int, name: String): EcuConfig {
        val view = layoutInflater.inflate(R.layout.sim_main_ecu_config_gui, contentFrame, false)
        val allSignals = libautodiag.getSimSignals().sortedBy { it.name.lowercase() }
        val addedSignalPaths = linkedSetOf<String>()
        val dynamicSignalsContainer = view.findViewById<LinearLayout>(R.id.signal_container)

        fun getSignalInitialValue(signal: SimSignal): Double {
            val v = libautodiag.getSignalValue(signal.path)
            if (!v.isNaN()) {
                return v
            }
            return signal.min
        }

        fun setSignalValue(signal: SimSignal, value: Double) {
            SimGeneratorGui.setSignalValue(signal.path, value)
        }

        fun addSignalWidget(signal: SimSignal) {
            if (addedSignalPaths.contains(signal.path)) {
                return
            }
            addedSignalPaths.add(signal.path)

            val title = TextView(this).apply {
                text = if (signal.unit.isNullOrBlank()) signal.name else "${signal.name} (${signal.unit})"
            }

            val step = if (signal.step <= 0.0) 1.0 else signal.step
            val scale = (1.0 / step).toInt().coerceAtLeast(1)
            val minI = (signal.min * scale).toInt()
            val maxI = (signal.max * scale).toInt()
            val initialI = (((getSignalInitialValue(signal)).coerceIn(signal.min, signal.max)) * scale).toInt()

            val valueText = TextView(this).apply {
                val shown = initialI.toDouble() / scale
                text = if (signal.unit.isNullOrBlank()) "$shown" else "$shown ${signal.unit}"
            }

            val seek = SeekBar(this).apply {
                max = (maxI - minI).coerceAtLeast(0)
                progress = (initialI - minI).coerceIn(0, max)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                        val v = (p + minI).toDouble() / scale
                        valueText.text = if (signal.unit.isNullOrBlank()) "$v" else "$v ${signal.unit}"
                        setSignalValue(signal, v)
                    }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {}
                })
            }

            setSignalValue(signal, initialI.toDouble() / scale)

            val removeBtn = Button(this).apply {
                text = "Remove"
            }

            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(removeBtn)
            }

            val block = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 12, 0, 12)
                addView(headerRow)
                addView(valueText)
                addView(seek)
            }

            removeBtn.setOnClickListener {
                addedSignalPaths.remove(signal.path)
                dynamicSignalsContainer.removeView(block)
            }

            dynamicSignalsContainer.addView(block)
        }

        val signalSpinner = view.findViewById<Spinner>(R.id.signal_choice)
        val spinnerSignals = allSignals
        val spinnerAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            spinnerSignals.map { it.name }
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        signalSpinner.adapter = spinnerAdapter

        view.findViewById<Button>(R.id.signal_add).apply {
            setOnClickListener {
                val index = signalSpinner.selectedItemPosition

                if (index in spinnerSignals.indices) {
                    val signal = spinnerSignals[index]
                    addSignalWidget(signal)
                }
            }
        }

        allSignals.firstOrNull { it.path == "SAEJ1979.engine_speed" }?.let { addSignalWidget(it) }
        allSignals.firstOrNull { it.path == "SAEJ1979.vehicle_speed" }?.let { addSignalWidget(it) }
        allSignals.firstOrNull { it.path == "SAEJ1979.coolant_temp" }?.let { addSignalWidget(it) }

        val dtcs = mutableListOf<String>()
        val dtcContainer = view.findViewById<LinearLayout>(R.id.dtc_list)

        fun addDtcRow(code: String) {
            if (code.isBlank()) return

            dtcs.add(code)
            SimGeneratorGui.dtcs.add(code)

            val text = TextView(this).apply {
                text = code
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val removeBtn = Button(this)
            removeBtn.text = "X"

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
                addView(text)
                addView(removeBtn)
            }

            removeBtn.setOnClickListener {
                dtcContainer.removeView(row)
                dtcs.remove(code)
                SimGeneratorGui.dtcs.remove(code)
            }

            dtcContainer.addView(row)
        }

        val dtcInput = view.findViewById<EditText>(R.id.dtc_entry)

        view.findViewById<Button>(R.id.dtc_entery_validate).apply {
            setOnClickListener {
                val v = dtcInput.text.toString().uppercase()
                if (v.isNotEmpty()) {
                    addDtcRow(v)
                    dtcInput.text.clear()
                }
            }
        }

        view.findViewById<CheckBox>(R.id.mil_state).apply {
            setOnCheckedChangeListener { _, v -> SimGeneratorGui.mil = v }
        }

        dtcClearedCheck = view.findViewById<CheckBox>(R.id.dtcs_cleared).apply {
            setOnCheckedChangeListener { _, v -> SimGeneratorGui.dtcCleared = v }
        }

        view.findViewById<EditText>(R.id.ecu_name).apply {
            addTextChangedListener { SimGeneratorGui.ecuName = it.toString() }
        }

        view.findViewById<EditText>(R.id.vin).apply {
            addTextChangedListener { SimGeneratorGui.vin = it.toString() }
        }
        libautodiag.setResponseGuiByAddress(address.toByte())
        val ecu = EcuConfig(
            id = address,
            name = name,
            type = EcuType.GUI,
            screen = view
        )
        return ecu
    }
    private fun updateScript(script: String, ecu: EcuConfig) {
        val errorReturn = ecu.screen.findViewById<TextView>(R.id.error_return)
        try {
            val handler = LuaJEcuHandler(script)

            // bind to ECU (native side)
            libautodiag.setResponseByteArrayByAddress(
                ecu.id.toByte(),
                handler
            )
            errorReturn.setText("parsing success")
        } catch (e: Exception) {
            errorReturn.setText("lua parsing error : ${e.message}")
        }
    }
    fun buildEcuConfig(address: Int, name: String, type: EcuType): EcuConfig {
        return when ( type ) {
            EcuType.GUI -> buildEcuGuiConfig(address, name)
            EcuType.SCRIPT -> {
                val view = layoutInflater.inflate(R.layout.sim_main_ecu_config_script, contentFrame, false)
                val luaEditor = view.findViewById<EditText>(R.id.lua_editor)

                val ecu = EcuConfig(
                    id = address,
                    name = name,
                    type = EcuType.SCRIPT,
                    screen = view
                )
                val applyScript = view.findViewById<Button>(R.id.apply_script)
                applyScript.setOnClickListener {
                    updateScript(luaEditor.text.toString(), ecu)
                }
                updateScript(luaEditor.text.toString(), ecu)
                ecu
            }
        }
    }

    fun openEcuConfig(ecu: EcuConfig) {
        screenStack.addLast(simView)
        show(ecu.screen)
    }

    fun addEcuRow(ecu: EcuConfig) {
        val row = layoutInflater.inflate(R.layout.sim_main_ecu_row, ecuListView, false)

        val title = row.findViewById<TextView>(R.id.ecu_title)

        title.text = "ECU 0x${ecu.id.toString(16).uppercase()} (${ecu.name})"

        row.setOnClickListener {
            openEcuConfig(ecu)
        }

        ecuListView.addView(row)
    }


    private fun selectedType(): EcuType {
        val selected = ecuAddSelect.selectedItem
        return selected as EcuType
    }

    private fun buildAddECUToGUI(address: Int, name: String, type: EcuType) {

        // iterate backwards to safely remove
        val offset_in_layout = 1
        for (i in ecus.indices.reversed()) {
            val ecu = ecus[i]

            if (ecu.id.toByte() == address.toByte()) {
                ecuListView.removeViewAt(i + offset_in_layout)
                ecus.removeAt(i)
            }
        }

        val ecu = buildEcuConfig(address, name, type)

        ecus.add(ecu)
        addEcuRow(ecu)
    }

    private fun setupSimView(view: View) {
        ecuListView = view.findViewById<ViewGroup>(R.id.ecu_list)
        val addEcuBtn = view.findViewById<Button>(R.id.add_ecu)
        ecuAddSelect = view.findViewById(R.id.ecu_type_spinner)
        val types = EcuType.values().toList()

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            types
        )

        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        ecuAddSelect.adapter = adapter
        addEcuBtn.setOnClickListener {
            val type = selectedType()

            buildAddECUToGUI(0xE8, "override (${type})", type)
        }

        var running = false
        view.findViewById<Button>(R.id.sim_state).apply {
            setOnClickListener {
                if (isPermissionsGranted()) {
                    running = !running
                    text = if (running) "Stop Sim" else "Start Sim"
                    if (running) startServer() else stopServer()
                } else {
                    requestPermissions()
                }
            }
        }
    }

    public lateinit var logRepo: LogRepository

    private fun buildSettingsView(): View {
        val view = layoutInflater.inflate(R.layout.settings, null)

        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

        val btContainer = view.findViewById<LinearLayout>(R.id.btContainer)
        val btNameEdit = view.findViewById<EditText>(R.id.btNameEdit)
        val btApplyBtn = view.findViewById<Button>(R.id.btApplyBtn)

        val logLevelSpinner = view.findViewById<Spinner>(R.id.logLevelSpinner)

        val networkSpinner = view.findViewById<Spinner>(R.id.networkSpinner)
        val protocolSpinner = view.findViewById<Spinner>(R.id.protocolSpinner)

        // ---------- Bluetooth ----------
        val adapterName = if (isPermissionsGranted()) btAdapter.name ?: "" else "Missing permission"
        btNameEdit.setText(adapterName)

        btApplyBtn.setOnClickListener {
            val newName = btNameEdit.text.toString().trim()
            if (newName.isNotEmpty()) {
                if (isPermissionsGranted()) btAdapter.name = newName
                else requestPermissions()
            }
        }

        // ---------- Log level ----------
        val logLevels = LogLevel.values().toList()

        logLevelSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            logLevels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        val savedLogLevel = prefs.getInt("log_level", LogLevel.INFO.ordinal)
        logLevelSpinner.setSelection(savedLogLevel)

        logLevelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                prefs.edit().putInt("log_level", pos).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // ---------- Network ----------
        val networks = listOf("Bluetooth", "Bluetooth LE (4.0+)", "Network")

        networkSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            networks
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        val savedNetwork = prefs.getInt("network_mode", NETWORK_BT)
        networkSpinner.setSelection(savedNetwork)

        btContainer.visibility =
            if (savedNetwork == NETWORK_BT || savedNetwork == NETWORK_BLE) View.VISIBLE else View.GONE

        networkSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                prefs.edit().putInt("network_mode", pos).apply()

                btContainer.visibility =
                    if (pos == NETWORK_BT || pos == NETWORK_BLE) View.VISIBLE else View.GONE
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // ---------- ELM ----------
        val protocols = libautodiag.getProtocols()
        val currentProto = libautodiag.getProtocol()
        val offset = 1

        protocolSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            protocols
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        val index = currentProto - offset
        if (index in protocols.indices) {
            protocolSpinner.setSelection(index)
        }

        protocolSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                libautodiag.setProtocol(pos + offset)
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        return view
    }


    private fun show(view: View) {
        contentFrame.removeAllViews()
        contentFrame.addView(view)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Use XML layout instead of building everything in code
        setContentView(R.layout.activity_main)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (screenStack.isNotEmpty()) {
                        show(screenStack.removeLast())
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )

        // ---- System services ----
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        btAdapter = manager.adapter

        // ---- Init core components ----
        MainActivityRef.activity = this
        bleBridge = BLEBridge(this, btAdapter)
        btBridge = BluetoothBridge(this, btAdapter)
        ntBridge = NetworkBridge(this)
        logRepo = LogRepository(this)

        // ---- Bind views from XML ----
        drawer = findViewById(R.id.drawer)
        contentFrame = findViewById(R.id.contentFrame)
        val navView: NavigationView = findViewById(R.id.navView)
        val toolbar: Toolbar = findViewById(R.id.toolbar)

        // ---- Toolbar setup ----
        setSupportActionBar(toolbar)
        val toggle = androidx.appcompat.app.ActionBarDrawerToggle(
            this,
            drawer,
            toolbar,
            R.string.open,
            R.string.close
        )

        drawer.addDrawerListener(toggle)
        toggle.syncState()

        // ---- Inflate screens ----
        simView = layoutInflater.inflate(R.layout.sim_main, contentFrame, false)
        setupSimView(simView)

        logView = LogView(this)
        settingsView = buildSettingsView()
        statsView = StatsView(this)

        // Default screen
        buildAddECUToGUI(0xE8, "default (gui)", EcuType.GUI)
        show(simView)

        // ---- Navigation drawer handling ----
        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_sim -> show(simView)
                R.id.nav_log -> show(logView)
                R.id.nav_stats -> show(statsView)
                R.id.nav_settings -> show(settingsView)
            }
            drawer.closeDrawer(Gravity.LEFT)
            true
        }

        // ---- Handle system bars (status + navigation) ----
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(contentFrame) { v, insets ->
            val systemBars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars()
            )

            v.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom
            )

            insets
        }

        // ---- Permissions ----
        if (!isPermissionsGranted()) {
            requestPermissions()
        }
    }

    fun setDtcClearedUi(value: Boolean) {
        dtcClearedCheck.isChecked = value
    }

    public fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun stopServer() {

        bleBridge.stop()
        btBridge.stop()
        ntBridge.stop()

        scope.coroutineContext.cancelChildren()

        appendLog("Bluetooth server stopped", LogLevel.INFO)
    }

    public fun clearSocketFiles() {
        val dir = filesDir
        dir.listFiles()?.forEach { f ->
            if (f.name.startsWith("socket")) {
                f.delete()
            }
        }
    }

    public fun showBluetoothEnablePopup() {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        enableBtLauncher.launch(intent)
    }

    private fun startServer() {
        when (prefs.getInt("network_mode", NETWORK_BT)) {
            NETWORK_BT  -> btBridge.start()
            NETWORK_BLE -> bleBridge.start()
            NETWORK_IP -> ntBridge.start()
            else -> appendLog("Network mode not implemented", LogLevel.DEBUG)
        }
    }

    fun hexDumpPretty(
        data: ByteArray,
        length: Int
    ): String {
        val charsPerLine = logView.charsPerLine
        val sb = StringBuilder()

        // fixed layout parts
        val indent = 1
        val hexByteWidth = 3      // "FF "
        val asciiSeparator = 2    // "  "

        // reserve space for ASCII area (~1 char per byte)
        val usable = charsPerLine - indent - asciiSeparator

        // each byte takes ~4 chars in total (hex + space)
        val bytesPerLine = (usable / 4).coerceIn(4, 64)

        for (i in 0 until length step bytesPerLine) {

            sb.append(" ")

            val lineEnd = minOf(i + bytesPerLine, length)

            // HEX PART
            for (j in i until i + bytesPerLine) {
                if (j < lineEnd) {
                    sb.append(String.format("%02X ", data[j]))
                } else {
                    sb.append("   ")
                }
            }

            sb.append("  ")

            // ASCII PART
            for (j in i until lineEnd) {
                val b = data[j].toInt() and 0xFF
                val c = if (b in 32..126) b.toChar() else '.'
                sb.append(c)
            }

            sb.append("\n")
        }

        return sb.toString()
    }

    fun onDataReceived(data: ByteArray, size_used: Int) {
        appendLog("recv : \n" + hexDumpPretty(data, size_used), LogLevel.DEBUG)
        statsView.onDataReceived(data, size_used)
    }

    fun onDataSent(data: ByteArray, size_used: Int) {
        appendLog("send : \n" + hexDumpPretty(data, size_used), LogLevel.DEBUG)
        statsView.onDataSent(data, size_used)
    }

    fun appendLog(text: String, level: LogLevel = LogLevel.DEBUG) {
        logView.append(text, level)
    }

    override fun onDestroy() {
        MainActivityRef.activity = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // Add logic here
        }
    }
}