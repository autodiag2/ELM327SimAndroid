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
import android.net.Uri
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.content.res.AppCompatResources
import com.github.autodiag2.elm327emu.com.BLEBridge
import com.github.autodiag2.elm327emu.com.BluetoothBridge
import com.github.autodiag2.elm327emu.com.Bridge
import com.github.autodiag2.elm327emu.com.NetworkBridge
import com.github.autodiag2.elm327emu.sim.SimSummary
import com.github.autodiag2.elm327emu.sim.Sim
import com.github.autodiag2.elm327emu.sim.SimList
import com.github.autodiag2.elm327emu.ui.NestedScreen
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

    private lateinit var bridges: List<Bridge>

    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    lateinit var contentFrame: FrameLayout
    private lateinit var drawer: DrawerLayout
    lateinit var toolbar: Toolbar

    lateinit var simView: Sim
    private lateinit var settingsView: View
    lateinit var logView: LogView
    lateinit var statsView: StatsView
    private lateinit var simListView: SimList
    private var activeScreen: View? = null

    private val screenStack = ArrayDeque<View?>()
    var pendingExportConfig: SimSummary? = null
    val prefs by lazy { getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }

    val exportLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->

            val uri = result.data?.data ?: return@registerForActivityResult
            val config = pendingExportConfig ?: return@registerForActivityResult

            contentResolver.openOutputStream(uri)?.use { output ->
                val data = config.file.readBytes()
                output.write(data)
            }

            Toast.makeText(
                this,
                getString(R.string.sim_list_exported_file),
                Toast.LENGTH_SHORT
            ).show()

            pendingExportConfig = null
        }

    private lateinit var filePickerLauncher: ActivityResultLauncher<String>
    private var pendingFileCallback: ((String) -> Unit)? = null

    fun launchJsonPicker(callback: (String) -> Unit) {
        pendingFileCallback = callback
        filePickerLauncher.launch("application/json")
    }

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
            AppCompatResources.getDrawable(this, R.drawable.baseline_menu_24)
    }

    fun showBackArrow() {
        toolbar.navigationIcon =
            AppCompatResources.getDrawable(this, R.drawable.arrow_back_24dp)
    }

    fun handleBack() {
        if (screenStack.isNotEmpty()) {
            val currentScreen = activeScreen as NestedScreen
            currentScreen.onBack()
            val previousScreen: View? = screenStack.removeLast()
            if ( previousScreen != null ) {
                show(previousScreen)
            }

            if (screenStack.isEmpty()) {
                showHamburger()
            } else {
                showBackArrow()
            }
        } else {
            drawer.openDrawer(Gravity.LEFT)
        }
    }

    fun showNestedScreen(view: View) {
        screenStack.addLast(activeScreen)
        show(view)
        showBackArrow()
    }

    private fun show(view: View) {
        contentFrame.removeAllViews()
        contentFrame.addView(view)
        activeScreen = view

        invalidateOptionsMenu()
    }

    var isSimItemSelectedMode = false
        set(value) {
            field = value
            invalidateOptionsMenu() // forces onPrepareOptionsMenu refresh
        }

    override fun onPrepareOptionsMenu(menu: android.view.Menu): Boolean {

        menu.setGroupVisible(R.id.action_menu_group_sim, activeScreen == simView)
        menu.setGroupVisible(R.id.action_menu_group_sim_list,
            activeScreen == simListView &&
            isSimItemSelectedMode
        )

        return super.onPrepareOptionsMenu(menu)
    }

    var lastConfigName: String = "car"

    fun showSaveAsDialog() {
        val input = android.widget.EditText(this).apply {
            setText(lastConfigName)
        }

        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.sim_list_save_title)
            .setView(input)
            .setPositiveButton(getString(R.string.sim_list_save_validate)) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    lastConfigName = name
                    val file = File(filesDir, "config/${name}.json")
                    file.parentFile?.mkdirs()
                    simView.saveConfig(file.absolutePath)
                    appendLog(getString(R.string.sim_list_log_save_success, name), LogLevel.INFO)
                }
            }
            .setNegativeButton(R.string.sim_list_save_cancel, null)
            .show()
    }

    // -------------------------------
    // Lifecycle
    // -------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        filePickerLauncher = registerForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            val callback = pendingFileCallback
            pendingFileCallback = null  // avoid reuse / leaks

            if (uri != null && callback != null) {
                val text = contentResolver.openInputStream(uri)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: ""

                callback(text)
            }
        }

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
        bridges = listOf(
            BluetoothBridge(this, btAdapter), // NETWORK_BT
            BLEBridge(this, btAdapter),       // NETWORK_BLE
            NetworkBridge(this)               // NETWORK_IP
        )

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
        simView = Sim(this)
        logView = LogView(this)
        settingsView = SettingsView(this)
        statsView = StatsView(this)
        simListView = SimList(this) { file ->
            simView.loadConfig(file.absolutePath)
            handleBack()
        }

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
        menuInflater.inflate(R.menu.menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {

            R.id.sim_menu_clear -> {
                simView.ecuClear()
                SimGeneratorGuiManager.clear()
                true
            }

            R.id.sim_menu_load_latest -> {
                val dir = File(filesDir, "config")
                val latest = dir.listFiles()
                    ?.filter { it.extension == "json" }
                    ?.maxByOrNull { it.lastModified() }

                if (latest != null) {
                    simView.loadConfig(latest.absolutePath)
                    appendLog(getString(R.string.sim_list_load_latest_success, latest.name), LogLevel.INFO)
                } else {
                    appendLog(getString(R.string.sim_list_load_latest_no_config), LogLevel.ERROR)
                }
                true
            }

            R.id.sim_menu_import_clipboard -> {

                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = clipboard.primaryClip

                val text = clip?.getItemAt(0)?.text?.toString()

                if (text.isNullOrBlank()) {
                    return true
                }

                try {
                    simView.loadConfigJSON(text)
                } catch (e: Exception) {
                }

                true
            }

            R.id.sim_menu_export_clipboard -> {

                try {
                    val json = simView.saveConfigAsJson()

                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("ECU Config", json)

                    clipboard.setPrimaryClip(clip)

                } catch (e: Exception) {
                }

                true
            }

            R.id.sim_menu_load -> {
                showNestedScreen(simListView)
                true
            }

            R.id.sim_menu_save_as -> {
                showSaveAsDialog()
                true
            }

            // sims screen actions
            R.id.sim_list_menu_delete -> {
                simListView.onDelete()
                true
            }
            R.id.sim_list_menu_export -> {
                simListView.onExport()
                true
            }
            R.id.sim_list_menu_export_file -> {
                simListView.onExportFile()
                true
            }
            R.id.sim_list_menu_open -> {
                simListView.onOpenSimConfig()
                true
            }
            R.id.sim_list_menu_share -> {
                simListView.shareConfigAsText()
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

    fun stopServer() {
        for(bridge in bridges) {
            bridge.stop()
        }
        scope.coroutineContext.cancelChildren()
        appendLog(getString(R.string.log_main_bluetooth_server_stopped), LogLevel.INFO)
    }

    fun clearSocketFiles() {
        filesDir.listFiles()?.forEach {
            if (it.name.startsWith("socket")) it.delete()
        }
    }

    fun showBluetoothEnablePopup() {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        enableBtLauncher.launch(intent)
    }

    fun startServer() {
        bridges[prefs.getInt("network_mode", NETWORK_BT)].start()
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