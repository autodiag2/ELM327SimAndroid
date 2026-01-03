package com.autodiag.elm327emu

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
import com.autodiag.elm327emu.libautodiag
import android.util.Log
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.widget.FrameLayout
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import android.view.MenuItem
import android.content.Intent
import com.autodiag.elm327emu.SimGeneratorGui
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
import com.autodiag.elm327emu.R
import com.autodiag.elm327emu.BluetoothBridge
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

import androidx.lifecycle.lifecycleScope

import kotlinx.coroutines.flow.collectLatest
import androidx.paging.cachedIn

private const val REQUEST_CODE = 1
private const val REQUEST_SAVE_LOG = 1001

class MainActivity : AppCompatActivity() {
    private val btAdapter = BluetoothAdapter.getDefaultAdapter()
    private val classicalBtUUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    // Order in the settings screen
    private val NETWORK_BT = 0
    private val NETWORK_BLE = 1
    private val NETWORK_IP = 2

    lateinit var bleBridge: BLEBridge
    lateinit var btBridge: BluetoothBridge
    lateinit var ntBridge: NetworkBridge

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    lateinit var contentFrame: FrameLayout
    private lateinit var drawer: DrawerLayout

    lateinit var simView: View
    private lateinit var dtcClearedCheck: CheckBox
    
    lateinit var logViewRoot: View

    lateinit var settingsView: View

    private val prefs by lazy { getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    private val autoScroll get() = prefs.getBoolean("auto_scroll", true)

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

    private fun buildSimView(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        fun labeledSeekBar(label: String, min: Int, max: Int, unit: String, onChange: (Int) -> Unit) {
            val title = TextView(this).apply { text = label }
            val value = TextView(this).apply { text = "$min $unit" }
            val seek = SeekBar(this).apply {
                this.max = max - min
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, f: Boolean) {
                        val v = p + min
                        value.text = "$v $unit"
                        onChange(v)
                    }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {}
                })
            }
            container.addView(title)
            container.addView(value)
            container.addView(seek)
        }

        labeledSeekBar("Vehicle speed (km/h)", 0, 250, "km/h") {
            SimGeneratorGui.vehicleSpeed = it
        }

        labeledSeekBar("Coolant temperature (°C)", -40, 150, "°C") {
            SimGeneratorGui.coolantTemp = it
        }

        labeledSeekBar("Engine speed (r/min)", 0, 8000, "rpm") {
            SimGeneratorGui.engineRpm = it
        }

        val dtcs = mutableListOf<String>()
        val dtcAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, dtcs)

        val listView = ListView(this).apply {
            adapter = dtcAdapter
        }

        val dtcInput = EditText(this).apply {
            hint = "P0103"
        }

        val addButton = Button(this).apply {
            text = "Add"
            setOnClickListener {
                val v = dtcInput.text.toString()
                if (v.isNotEmpty()) {
                    dtcs.add(v)
                    SimGeneratorGui.dtcs.add(v)
                    dtcAdapter.notifyDataSetChanged()
                    dtcInput.text.clear()
                }
            }
        }

        val dtcRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(dtcInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(addButton)
        }

        container.addView(
            listView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                300
            )
        )
        container.addView(dtcRow)

        val milCheck = CheckBox(this).apply {
            text = "MIL status"
            setOnCheckedChangeListener { _, v -> SimGeneratorGui.mil = v }
        }

        dtcClearedCheck = CheckBox(this).apply {
            text = "DTCs cleared"
            setOnCheckedChangeListener { _, v -> SimGeneratorGui.dtcCleared = v }
        }

        container.addView(milCheck)
        container.addView(dtcClearedCheck)

        val ecuName = EditText(this).apply {
            hint = "ECU name"
            setText(SimGeneratorGui.ecuName)
            addTextChangedListener { SimGeneratorGui.ecuName = it.toString() }
        }

        val vin = EditText(this).apply {
            hint = "VF7RD5FV8FL507366"
            setText(SimGeneratorGui.vin)
            addTextChangedListener { SimGeneratorGui.vin = it.toString() }
        }

        container.addView(ecuName)
        container.addView(vin)

        var running = false
        val startStop = Button(this).apply {
            text = "Start simulation"
            setOnClickListener {
                if (isPermissionsGranted()) {
                    running = !running
                    text = if (running) "Stop simulation" else "Start simulation"
                    if (running) startServer() else stopServer()
                } else {
                    requestPermissions()
                }
            }
        }

        container.addView(startStop)

        return ScrollView(this).apply {
            isFillViewport = true
            addView(container)
        }
    }

    private val logRepo = LogRepository()
    private lateinit var logAdapter: LogAdapter

    private fun buildLogView(): View {
        logAdapter = LogAdapter()

        val root = FrameLayout(this)

        val vertical = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        fun buildButtons(): LinearLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16, 16, 16, 16)

                addView(Button(this@MainActivity).apply {
                    text = "Download log"
                    setOnClickListener {
                        scope.launch {
                            val file = File(getExternalFilesDir(null), "elm327emu_log.txt")
                            file.writeText(logRepo.snapshotUnsafe().joinToString("\n") { it.text })
                            appendLog("Log written to: ${file.absolutePath}", LogLevel.INFO)
                        }
                    }
                })

                addView(Button(this@MainActivity).apply {
                    text = "Download log on FS"
                    setOnClickListener { openSaveLogDialog() }
                })

                addView(Button(this@MainActivity).apply {
                    text = "Clear log"
                    setOnClickListener {
                        logAdapter.submitData(lifecycle, PagingData.empty())
                    }
                })
            }

        val buttons = buildButtons()
        vertical.addView(
            buttons,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = false
            }
            adapter = logAdapter
            itemAnimator = null
        }

        vertical.addView(
            rv,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        root.addView(vertical)

        lifecycleScope.launch {
            logRepo.pager()
                .flow
                .cachedIn(this)
                .collectLatest {
                    logAdapter.submitData(it)
                }
        }

        return root
    }


    private fun buildSettingsView(): View {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            gravity = Gravity.TOP
        }

        val generalTitle = TextView(this).apply {
            text = "General"
            textSize = 18f
        }
        root.addView(generalTitle)

        root.addView(Switch(this).apply {
            text = "Auto-scroll on output"
            isChecked = prefs.getBoolean("auto_scroll", true)
            setOnCheckedChangeListener { _, v ->
                prefs.edit().putBoolean("auto_scroll", v).apply()
            }
        })
        val btNameContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 0)
        }

        val btNameLabel = TextView(this).apply {
            text = "Bluetooth device name"
        }
        btNameContainer.addView(btNameLabel)

        var adapterName = "Missing permission"
        if ( isPermissionsGranted() ) {
            adapterName = btAdapter?.name ?: ""
        }
        val btNameEdit = EditText(this).apply {
            hint = "OBD II"
            setText(adapterName)
        }
        btNameContainer.addView(btNameEdit)

        val applyBtn = Button(this).apply {
            text = "Set"
            setOnClickListener {
                val newName = btNameEdit.text.toString().trim()
                if (newName.isEmpty() || btAdapter == null) return@setOnClickListener

                if (isPermissionsGranted()) {
                    btAdapter.name = newName
                } else {
                    requestPermissions()
                }
            }
        }
        btNameContainer.addView(applyBtn)

        root.addView(btNameContainer)

        val logLevelLabel = TextView(this).apply {
            text = "Log level"
            setPadding(0, 16, 0, 0)
        }
        root.addView(logLevelLabel)

        val logLevels = LogLevel.values().toList()

        val logLevelSpinner = Spinner(this)

        val logLevelAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            logLevels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        logLevelSpinner.adapter = logLevelAdapter

        val savedLogLevel = prefs.getInt("log_level", LogLevel.INFO.ordinal)
        if (savedLogLevel in logLevels.indices) {
            logLevelSpinner.setSelection(savedLogLevel)
        }

        logLevelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: View?,
                pos: Int,
                id: Long
            ) {
                prefs.edit().putInt("log_level", pos).apply()
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        root.addView(logLevelSpinner)

        val networkLabel = TextView(this).apply {
            text = "Networking mode"
            setPadding(0, 16, 0, 0)
        }
        root.addView(networkLabel)

        val networks = listOf(
            "Bluetooth",
            "Bluetooth LE (4.0+)",
            "Network"
        )

        val networkSpinner = Spinner(this)

        val networkAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            networks
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        networkSpinner.adapter = networkAdapter

        val savedNetwork = prefs.getInt("network_mode", NETWORK_BT)
        if (savedNetwork in networks.indices) {
            networkSpinner.setSelection(savedNetwork)
        }
        btNameContainer.visibility =
            if (savedNetwork == NETWORK_BLE || savedNetwork == NETWORK_BT) View.VISIBLE else View.GONE

        networkSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: View?,
                pos: Int,
                id: Long
            ) {
                prefs.edit().putInt("network_mode", pos).apply()
                 btNameContainer.visibility =
                    if (pos == NETWORK_BLE || pos == NETWORK_BT) View.VISIBLE else View.GONE
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        root.addView(networkSpinner)

        val elmTitle = TextView(this).apply {
            text = "ELM327 parameters"
            textSize = 18f
            setPadding(0, 32, 0, 0)
        }
        root.addView(elmTitle)

        val protocols = libautodiag.getProtocols()
        val currentProto = libautodiag.getProtocol()
        val PROTOCOL_OFFSET = 1

        val spinner = Spinner(this)
        val protocolAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            protocols
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        spinner.adapter = protocolAdapter

        val index = currentProto - PROTOCOL_OFFSET
        if (index in protocols.indices) {
            spinner.setSelection(index)
        }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: View?,
                pos: Int,
                id: Long
            ) {
                libautodiag.setProtocol(pos + PROTOCOL_OFFSET)
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        root.addView(spinner)

        return ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
    }


    private fun show(view: View) {
        contentFrame.removeAllViews()
        contentFrame.addView(view)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MainActivityRef.activity = this
        bleBridge = BLEBridge(this, btAdapter)
        btBridge = BluetoothBridge(this, btAdapter)
        ntBridge = NetworkBridge(this)

        drawer = DrawerLayout(this)

        simView = buildSimView()
        logViewRoot = buildLogView()
        settingsView = buildSettingsView()
        
        contentFrame = FrameLayout(this)
        contentFrame.addView(simView)

        val navView = NavigationView(this).apply {
            menu.add("Sim").setOnMenuItemClickListener {
                show(simView)
                drawer.closeDrawer(Gravity.LEFT)
                true
            }

            menu.add("Log").setOnMenuItemClickListener {
                show(logViewRoot)
                drawer.closeDrawer(Gravity.LEFT)
                true
            }

            menu.add("Settings").setOnMenuItemClickListener {
                show(settingsView)
                drawer.closeDrawer(Gravity.LEFT)
                true
            }

        }

        drawer.addView(
            contentFrame,
            DrawerLayout.LayoutParams(
                DrawerLayout.LayoutParams.MATCH_PARENT,
                DrawerLayout.LayoutParams.MATCH_PARENT
            )
        )

        drawer.addView(
            navView,
            DrawerLayout.LayoutParams(
                DrawerLayout.LayoutParams.WRAP_CONTENT,
                DrawerLayout.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.START }
        )

        setContentView(drawer)

        if (!isPermissionsGranted()) {
            requestPermissions()
        }
    }

    private fun openSaveLogDialog() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "elm327emu_log.txt")
        }
        startActivityForResult(intent, REQUEST_SAVE_LOG)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_SAVE_LOG && resultCode == RESULT_OK) {
            val uri = data?.data ?: return

            scope.launch(Dispatchers.IO) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    val text = logRepo.snapshotUnsafe()
                        .joinToString("\n") { it.text }
                    out.write(text.toByteArray())
                }
            }
        }
    }

    fun setDtcClearedUi(value: Boolean) {
        dtcClearedCheck.isChecked = value
    }

    private fun dpToPx(dp: Int): Int {
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
        startActivityForResult(intent, REQUEST_CODE)
    }

    private fun startServer() {
        when (prefs.getInt("network_mode", NETWORK_BT)) {
            NETWORK_BT  -> btBridge.start()
            NETWORK_BLE -> bleBridge.start()
            NETWORK_IP -> ntBridge.start()
            else -> appendLog("Network mode not implemented", LogLevel.DEBUG)
        }
    }

    fun appendLog(text: String, level: LogLevel = LogLevel.DEBUG) {
        val currentLogLevel = prefs.getInt("log_level", LogLevel.INFO.ordinal)
        if (currentLogLevel < level.ordinal) return

        scope.launch {
            logRepo.append(text, level)
            withContext(Dispatchers.Main) {
                logAdapter.refresh()
            }
        }
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