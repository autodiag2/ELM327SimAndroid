package com.github.autodiag2.elm327emu

import android.Manifest
import androidx.appcompat.app.AppCompatActivity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import kotlinx.coroutines.*
import java.io.*
import java.net.Socket
import java.util.UUID
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.github.autodiag2.elm327emu.libautodiag
import android.util.Log
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.widget.FrameLayout
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import android.view.MenuItem
import android.content.Intent
import com.github.autodiag2.elm327emu.SimGeneratorGui
import androidx.appcompat.widget.Toolbar
import android.content.Context
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ListView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Button
import android.widget.CheckBox
import android.widget.ScrollView
import androidx.core.widget.addTextChangedListener
import com.github.autodiag2.elm327emu.R
import com.github.autodiag2.elm327emu.BluetoothBridge
import android.view.ViewGroup.LayoutParams
import android.view.View
import android.content.SharedPreferences
import android.widget.*
import android.view.MotionEvent
import android.bluetooth.*
import android.bluetooth.le.*
import android.os.ParcelUuid
import java.io.InputStream
import java.io.OutputStream
import android.bluetooth.BluetoothManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.text.Spannable
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.paging.PagingData
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.math.roundToInt
import androidx.lifecycle.lifecycleScope

import kotlinx.coroutines.flow.collectLatest
import androidx.paging.cachedIn
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

private const val REQUEST_CODE = 1

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

    lateinit var settingsView: View
    lateinit var logView: LogView

    public val prefs by lazy { getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }

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

    private fun setupSimView(simView: View) {

        val allSignals = libautodiag.getSimSignals().sortedBy { it.name.lowercase() }
        val addedSignalPaths = linkedSetOf<String>()
        val dynamicSignalsContainer = simView.findViewById<LinearLayout>(R.id.signal_container)

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

        val signalSpinner = simView.findViewById<Spinner>(R.id.signal_choice)
        val spinnerSignals = allSignals
        val spinnerAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            spinnerSignals.map { it.name }
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        signalSpinner.adapter = spinnerAdapter

        simView.findViewById<Button>(R.id.signal_add).apply {
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
        val dtcContainer = simView.findViewById<LinearLayout>(R.id.dtc_list)

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

        val dtcInput = simView.findViewById<EditText>(R.id.dtc_entry)

        simView.findViewById<Button>(R.id.dtc_entery_validate).apply {
            setOnClickListener {
                val v = dtcInput.text.toString().uppercase()
                if (v.isNotEmpty()) {
                    addDtcRow(v)
                    dtcInput.text.clear()
                }
            }
        }

        simView.findViewById<CheckBox>(R.id.mil_state).apply {
            setOnCheckedChangeListener { _, v -> SimGeneratorGui.mil = v }
        }

        dtcClearedCheck = simView.findViewById<CheckBox>(R.id.dtcs_cleared).apply {
            setOnCheckedChangeListener { _, v -> SimGeneratorGui.dtcCleared = v }
        }

        simView.findViewById<EditText>(R.id.ecu_name).apply {
            addTextChangedListener { SimGeneratorGui.ecuName = it.toString() }
        }

        simView.findViewById<EditText>(R.id.vin).apply {
            addTextChangedListener { SimGeneratorGui.vin = it.toString() }
        }

        var running = false
        simView.findViewById<Button>(R.id.sim_state).apply {
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
        val maxLogEdit = view.findViewById<EditText>(R.id.maxLogEdit)

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

        // ---------- Max logs ----------
        val savedMax = prefs.getInt("log_max_entries", logRepo.LOG_MAX_ENTRIES)
        maxLogEdit.setText(savedMax.toString())

        maxLogEdit.addTextChangedListener {
            val v = it?.toString()?.toIntOrNull() ?: return@addTextChangedListener
            if (v >= 100) {
                prefs.edit().putInt("log_max_entries", v).apply()
            }
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
        simView = layoutInflater.inflate(R.layout.sim, contentFrame, false)
        setupSimView(simView)

        logView = LogView(this)
        settingsView = buildSettingsView()

        // Default screen
        show(simView)

        // ---- Navigation drawer handling ----
        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_sim -> show(simView)
                R.id.nav_log -> show(logView)
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

    fun onDataReceived(data: ByteArray, size_used: Int) {
        appendLog("recv : \n" + hexDump(data, size_used), LogLevel.DEBUG)
    }

    fun onDataSent(data: ByteArray, size_used: Int) {
        appendLog("send : \n" + hexDump(data, size_used), LogLevel.DEBUG)
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