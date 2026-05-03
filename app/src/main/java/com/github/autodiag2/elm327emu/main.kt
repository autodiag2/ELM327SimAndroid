package com.github.autodiag2.elm327emu

import android.Manifest
import androidx.appcompat.app.AppCompatActivity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.*
import android.bluetooth.BluetoothAdapter
import android.view.Gravity
import android.widget.FrameLayout
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import android.content.Intent
import androidx.appcompat.widget.Toolbar
import android.content.Context
import android.view.View
import android.bluetooth.BluetoothManager
import android.widget.CheckBox
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.content.res.AppCompatResources
import java.io.File

private const val REQUEST_CODE = 1

class MainActivity : AppCompatActivity() {

    lateinit var btAdapter: BluetoothAdapter

    private val enableBtLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    // Order in the settings screen
    val NETWORK_BT = 0
    val NETWORK_BLE = 1
    val NETWORK_IP = 2

    lateinit var bleBridge: BLEBridge
    lateinit var btBridge: BluetoothBridge
    lateinit var ntBridge: NetworkBridge

    public val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    lateinit var contentFrame: FrameLayout
    private lateinit var drawer: DrawerLayout
    lateinit var toolbar: Toolbar

    lateinit var dtcClearedCheck: CheckBox

    lateinit var simView: SimView
    lateinit var settingsView: View
    lateinit var logView: LogView
    lateinit var statsView: StatsView

    private val screenStack = ArrayDeque<View>()

    public val prefs by lazy { getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }

    // -------------------------------
    // Navigation handling
    // -------------------------------

    fun onToolbarClicked() {
        if (screenStack.isNotEmpty()) {
            handleBack()
        } else {
            if (drawer.isDrawerOpen(Gravity.LEFT)) {
                drawer.closeDrawer(Gravity.LEFT)
            } else {
                drawer.openDrawer(Gravity.LEFT)
            }
        }
    }

    fun showHamburger() {
        toolbar.navigationIcon =
            AppCompatResources.getDrawable(this, com.google.android.material.R.drawable.abc_ic_menu_overflow_material)
    }

    fun showBackArrow() {
        toolbar.navigationIcon =
            AppCompatResources.getDrawable(this, com.google.android.material.R.drawable.abc_ic_ab_back_material)
    }

    fun handleBack() {
        if (screenStack.isNotEmpty()) {
            show(screenStack.removeLast())

            if (screenStack.isEmpty()) {
                showHamburger()
            } else {
                showBackArrow()
            }
        } else {
            drawer.openDrawer(Gravity.LEFT)
        }
    }

    fun openEcuConfig(ecu: EcuConfig) {
        screenStack.addLast(simView)
        show(ecu.screen)
        showBackArrow()
    }

    fun openConfigSelection() {
        screenStack.addLast(simView)
        show(simView.buildLoadConfigView{ file ->
            simView.loadConfig(file.absolutePath)
            handleBack()
        })
        showBackArrow()
    }

    private fun show(view: View) {
        contentFrame.removeAllViews()
        contentFrame.addView(view)
    }

    lateinit var lastConfigName: String

    fun showSaveAsDialog() {
        val input = android.widget.EditText(this).apply {
            setText(lastConfigName)
        }

        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.sim_load_config_save_title)
            .setView(input)
            .setPositiveButton(getString(R.string.sim_load_config_save_validate)) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    lastConfigName = name
                    val file = File(filesDir, "config/${name}.json")
                    file.parentFile?.mkdirs()
                    simView.saveConfig(file.absolutePath)
                    appendLog(getString(R.string.sim_load_config_log_save_success, name), LogLevel.INFO)
                }
            }
            .setNegativeButton(R.string.sim_load_config_save_cancel, null)
            .show()
    }

    // -------------------------------
    // Lifecycle
    // -------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (screenStack.isNotEmpty()) {
                        handleBack()
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

        // ---- Bind views ----
        drawer = findViewById(R.id.drawer)
        contentFrame = findViewById(R.id.contentFrame)
        val navView: NavigationView = findViewById(R.id.navView)
        toolbar = findViewById(R.id.toolbar)

        // ---- Toolbar ----
        setSupportActionBar(toolbar)

        toolbar.setNavigationOnClickListener {
            onToolbarClicked()
        }

        showHamburger()

        // ---- Views ----
        simView = SimView(this)
        logView = LogView(this)
        settingsView = SettingsView(this)
        statsView = StatsView(this)

        // Default screen
        show(simView)

        // ---- Drawer navigation ----
        navView.setNavigationItemSelectedListener { item ->
            screenStack.clear() // reset stack when using drawer

            when (item.itemId) {
                R.id.nav_sim -> show(simView)
                R.id.nav_log -> show(logView)
                R.id.nav_stats -> show(statsView)
                R.id.nav_settings -> show(settingsView)
            }

            showHamburger()
            drawer.closeDrawer(Gravity.LEFT)
            true
        }

        // ---- Insets ----
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

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.sim_config, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {

            R.id.action_clear -> {
                simView.ecuClear()
                SimGeneratorGui.dtcs.clear()
                SimGeneratorGui.mil = false
                SimGeneratorGui.ecuName = ""
                SimGeneratorGui.vin = ""
                true
            }

            R.id.action_load_latest -> {
                val dir = File(filesDir, "config")
                val latest = dir.listFiles()
                    ?.filter { it.extension == "json" }
                    ?.maxByOrNull { it.lastModified() }

                if (latest != null) {
                    simView.loadConfig(latest.absolutePath)
                    appendLog(getString(R.string.sim_load_config_load_latest_success, latest.name), LogLevel.INFO)
                } else {
                    appendLog(getString(R.string.sim_load_config_load_latest_no_config), LogLevel.ERROR)
                }
                true
            }

            R.id.action_load -> {
                openConfigSelection()
                true
            }

            R.id.action_save_as -> {
                showSaveAsDialog()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    // -------------------------------
    // Permissions
    // -------------------------------

    fun isPermissionsGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) &&
                    (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED)
        } else true
    }

    fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
                ),
                REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    // -------------------------------
    // Logic
    // -------------------------------

    fun setDtcClearedUi(value: Boolean) {
        dtcClearedCheck.isChecked = value
    }

    fun stopServer() {
        bleBridge.stop()
        btBridge.stop()
        ntBridge.stop()
        scope.coroutineContext.cancelChildren()
        appendLog(getString(R.string.log_main_bluetooth_server_stopped), LogLevel.INFO)
    }

    public fun clearSocketFiles() {
        filesDir.listFiles()?.forEach {
            if (it.name.startsWith("socket")) it.delete()
        }
    }

    public fun showBluetoothEnablePopup() {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        enableBtLauncher.launch(intent)
    }

    fun startServer() {
        when (prefs.getInt("network_mode", NETWORK_BT)) {
            NETWORK_BT  -> btBridge.start()
            NETWORK_BLE -> bleBridge.start()
            NETWORK_IP  -> ntBridge.start()
            else -> appendLog(getString(R.string.log_main_network_mode_not_implemented), LogLevel.DEBUG)
        }
    }

    fun onDataReceived(data: ByteArray, size_used: Int) {
        appendLog(getString(R.string.log_main_data_received, logView.dataLogFormat(data, size_used)), LogLevel.DEBUG)
        statsView.onDataReceived(data, size_used)
    }

    fun onDataSent(data: ByteArray, size_used: Int) {
        appendLog(getString(R.string.log_main_data_sent, logView.dataLogFormat(data, size_used)), LogLevel.DEBUG)
        statsView.onDataSent(data, size_used)
    }

    fun appendLog(text: String, level: LogLevel = LogLevel.DEBUG) {
        logView.append(text, level)
    }

    override fun onDestroy() {
        MainActivityRef.activity = null
        super.onDestroy()
    }
}