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
import com.github.autodiag2.elm327emu.com.BridgeOrchestrator
import com.github.autodiag2.elm327emu.sim.SimSummary
import com.github.autodiag2.elm327emu.sim.Sim
import com.github.autodiag2.elm327emu.sim.SimList
import com.github.autodiag2.elm327emu.sim.SimView
import com.github.autodiag2.elm327emu.ui.NestedScreen
import com.github.autodiag2.elm327emu.ui.settings.SettingsFragment
import com.github.autodiag2.elm327emu.ui.settings.SETTING_DEFAULT_APP_EMU_AUTOSTART
import com.github.autodiag2.elm327emu.sim.serial.CustomController
import com.github.autodiag2.elm327emu.sim.serial.CustomView
import java.io.File
import com.github.autodiag2.elm327emu.sim.ecu.EcuGuiView
import com.github.autodiag2.elm327emu.sim.GuiGodot
import android.widget.Button
import com.github.autodiag2.elm327emu.LogEntryType
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.whenResumed
import android.util.Log
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder

private const val REQUEST_CODE = 1

class MainActivity : AppCompatActivity(), BridgeOrchestrator.ClientConnectionListener {

    public var emulatorService: EmulatorForegroundService? = null
    var service: EmulatorForegroundService?
        get() = emulatorService
        set(value) {
            emulatorService = value
        }

    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as EmulatorForegroundService.LocalBinder
            emulatorService = binder.getService()
            val connectedService = binder.getService()
            emulatorService = connectedService
            logController = connectedService.log
            assert(::logView.isInitialized)
            logController.attachView(logView)
            assert(::simView.isInitialized)
            connectedService.sim.attachView(simView)
            connectedService.stats.attachView(statsView)
            serviceBound = true

            // autostart
            if ( ! connectedService.sim.isRunning() ) {
                prefs.getBoolean("app_emu_autostart", SETTING_DEFAULT_APP_EMU_AUTOSTART).let { autostart ->
                    if (autostart) {
                        runOnUiThread {
                            simView.findViewById<Button>(R.id.sim_state).performClick()
                        }
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            emulatorService = null
            serviceBound = false
        }
    }

    private val enableBtLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                appendLog(getString(R.string.log_main_bluetooth_enabled), LogLevel.INFO)
                service?.bridgeOrchestrator?.setupBluetoothBridge()
                service?.bridgeOrchestrator?.setupBleBridge()
            } else {
                appendLog(getString(R.string.log_main_bluetooth_enable_failed), LogLevel.ERROR)
            }
        }

    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    lateinit var contentFrame: FrameLayout
    private lateinit var drawer: DrawerLayout
    lateinit var toolbar: Toolbar

    lateinit var simView: SimView
    lateinit var logView: LogView
    lateinit var logController: LogController
    private lateinit var settingsFragment: SettingsFragment
    private lateinit var settingsContainer: FrameLayout
    lateinit var statsView: StatsView
    private lateinit var simListView: SimList
    private var activeScreen: View? = null
    private val godotGui: GuiGodot? by lazy(LazyThreadSafetyMode.NONE) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            GuiGodot(this)
        } else {
            null
        }
    }

    val screenStack = ArrayDeque<View?>()
    var pendingExportConfig: SimSummary? = null
    val prefs by lazy { getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }

    private var pendingWifiHotspotPermCallback: ((Boolean) -> Unit)? = null
    private val nearbyWifiPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if ( pendingWifiHotspotPermCallback != null ) {
                pendingWifiHotspotPermCallback?.invoke(granted)
                pendingWifiHotspotPermCallback = null
            }
        }
    
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
    
    var fileExportPendingData: String? = null
    val fileExportLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->

            val uri = result.data?.data ?: return@registerForActivityResult
            val fileContent = fileExportPendingData ?: return@registerForActivityResult

            contentResolver.openOutputStream(uri)?.use { output ->
                output.write(fileContent.toByteArray())
            }

            Toast.makeText(
                this,
                getString(R.string.activity_export_file_success),
                Toast.LENGTH_SHORT
            ).show()

            fileExportPendingData = null
        }

    private lateinit var filePickerLauncher: ActivityResultLauncher<String>
    private var pendingFileCallback: ((String) -> Unit)? = null

    fun launchJsonPicker(callback: (String) -> Unit) {
        pendingFileCallback = callback
        filePickerLauncher.launch("application/json")
    }

    public var clients: List<BridgeOrchestrator.ConnectedClient>? = null

    override fun onClientConnectUpdate(
        clients: List<BridgeOrchestrator.ConnectedClient>
    ) {
        for (client in clients) {
            if (this.clients.orEmpty().none {
                    it.clientIdentifier == client.clientIdentifier
                } == true
            ) {
                service!!.sim.customSerialScreen.onClientConnected(
                    client.clientIdentifier
                )
            }
        }

        for (client in this.clients.orEmpty()) {
            if (clients.none {
                    it.clientIdentifier == client.clientIdentifier
                }
            ) {
                service!!.sim.customSerialScreen.onClientDisconnected(
                    client.clientIdentifier
                )
            }
        }
        this.clients = clients
        simView.updateConnectedClientsButton()
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
            if (activeScreen is NestedScreen) {
                (activeScreen as NestedScreen).onBack()
            } else if (godotGui?.isVisible() ?: false) {
                godotGui?.onDestroy()
            }

            val previousScreen = screenStack.removeLast()

            if (previousScreen != null) {
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
        if ( view is NestedScreen ) {
            view.onShow()
        }
    }

    private fun show(view: View) {
        if (supportFragmentManager.isStateSaved) {
            lifecycleScope.launch {
                lifecycle.whenResumed {
                    show(view)
                }
            }
            return
        }

        settingsContainer.visibility = View.GONE
        contentFrame.visibility = View.VISIBLE

        supportFragmentManager.popBackStack(
            null,
            androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE
        )

        contentFrame.removeAllViews()
        contentFrame.addView(view)

        activeScreen = view

        invalidateOptionsMenu()
    }

    private fun showSettings() {
        contentFrame.removeAllViews()
        contentFrame.visibility = View.GONE
        settingsContainer.visibility = View.VISIBLE
        if (!settingsFragment.isAdded) {
            supportFragmentManager
                .beginTransaction()
                .add(settingsContainer.id, settingsFragment)
                .commit()
        }

        activeScreen = null
        invalidateOptionsMenu()
    }

    var simListNumberOfSelectedItems = 0
        set(value) {
            field = value
            invalidateOptionsMenu() // forces onPrepareOptionsMenu refresh
        }

    override fun onPrepareOptionsMenu(menu: android.view.Menu): Boolean {

        menu.setGroupVisible(R.id.action_menu_group_sim, activeScreen == simView)

        menu.setGroupVisible(R.id.action_menu_group_sim_list_any_selection,
            activeScreen == simListView &&
            0 < simListNumberOfSelectedItems
        )
        menu.setGroupVisible(R.id.action_menu_group_sim_list_single_selection,
            activeScreen == simListView &&
            1 == simListNumberOfSelectedItems
        )
        menu.setGroupVisible(R.id.action_menu_group_sim_ecu_gui_default, 
            activeScreen is EcuGuiView
        )
        menu.setGroupVisible(R.id.action_menu_group_sim_custom_serial_script,
            activeScreen is CustomView
        )
        menu.setGroupVisible(R.id.action_menu_group_logview,
            activeScreen is LogView
        )
        var simCustomSerialScreenIsSomeSelection = false
        if ( activeScreen is CustomView ) {
            val simCustomSerialScreen = activeScreen as CustomView
            simCustomSerialScreenIsSomeSelection = simCustomSerialScreen.model!!.isSomeSelection()
        }
        menu.setGroupVisible(R.id.action_menu_group_sim_custom_serial_script_any_element_selected,
            activeScreen is CustomView && simCustomSerialScreenIsSomeSelection
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
                    service!!.sim.saveConfig(file.absolutePath)
                    appendLog(getString(R.string.sim_list_log_save_success, name), LogLevel.INFO)
                    simListView.refresh()
                }
            }
            .setNegativeButton(R.string.sim_list_save_cancel, null)
            .show()
    }

    // -------------------------------
    // Lifecycle
    // -------------------------------

    override fun onStart() {
        super.onStart()
        bindService(
            Intent(this, EmulatorForegroundService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onStop() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
            emulatorService = null
        }
        super.onStop()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MainActivityRef.activity = this
        setContentView(R.layout.activity_main)
        settingsContainer = findViewById(R.id.settingsContainer)

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
        settingsFragment = SettingsFragment()
        statsView = StatsView(this)
        simListView = SimList(this) { file ->
            service!!.sim.loadConfig(file.absolutePath)
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
                R.id.nav_settings -> showSettings()
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

        startForegroundService()
    }

    fun startForegroundService() {
        if ( service == null ) {
            val intent = Intent(this, EmulatorForegroundService::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    fun stopForegroundService() {
        if ( service != null ) {
            stopService(
                Intent(this, EmulatorForegroundService::class.java)
            )
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {

            R.id.sim_menu_clear -> {
                service!!.sim.ecuClear()
                SimGeneratorGuiManager.clear()
                true
            }

            R.id.sim_ecu_gui_menu_start_godot -> {
                godotGui?.show()
                true
            }

            R.id.sim_menu_load_latest -> {
                val dir = File(filesDir, "config")
                val latest = dir.listFiles()
                    ?.filter { it.extension == "json" }
                    ?.maxByOrNull { it.lastModified() }

                if (latest != null) {
                    service!!.sim.loadConfig(latest.absolutePath)
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
                    service!!.sim.loadConfigJSON(text)
                } catch (e: Exception) {
                }

                true
            }

            R.id.sim_menu_export_clipboard -> {

                try {
                    val json = service!!.sim.saveConfigAsJson()

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
            R.id.sim_custom_serial_script_export_clipboard -> {
                service!!.sim.customSerialScreen.onExportClipboard()
                true
            }
            R.id.sim_custom_serial_script_import_clipboard -> {
                service!!.sim.customSerialScreen.onImportClipboard()
                true
            }
            R.id.sim_custom_serial_script_import_file -> {
                service!!.sim.customSerialScreen.onImportFile()
                true
            }
            R.id.sim_custom_serial_script_export_file -> {
                service!!.sim.customSerialScreen.onExportFile()
                true
            }
            R.id.sim_custom_serial_script_export_share -> {
                service!!.sim.customSerialScreen.shareConfigAsText()
                true
            }
            R.id.sim_custom_serial_script_add_log -> {
                service!!.sim.customSerialScreen.onAddLog()
                true
            }
            R.id.sim_custom_serial_script_add_delay -> {
                service!!.sim.customSerialScreen.onAddDelay()
                true
            }
            R.id.sim_custom_serial_script_add_recv -> {
                service!!.sim.customSerialScreen.onAddRecv()
                true
            }
            R.id.sim_custom_serial_script_add_send -> {
                service!!.sim.customSerialScreen.onAddSend()
                true
            }
            R.id.sim_custom_serial_script_add_container -> {
                service!!.sim.customSerialScreen.onAddContainer()
                true
            }
            R.id.sim_custom_serial_script_add_and -> {
                service!!.sim.customSerialScreen.onAddAnd()
                true
            }
            R.id.sim_custom_serial_script_add_or -> {
                service!!.sim.customSerialScreen.onAddOr()
                true
            }
            R.id.sim_custom_serial_script_add_xor -> {
                service!!.sim.customSerialScreen.onAddXor()
                true
            }
            R.id.sim_custom_serial_script_add_not -> {
                service!!.sim.customSerialScreen.onAddNot()
                true
            }
            R.id.sim_custom_serial_script_add_client_connected -> {
                service!!.sim.customSerialScreen.onAddClientConnected()
                true
            }
            R.id.sim_custom_serial_script_add_client_disconnected -> {
                service!!.sim.customSerialScreen.onAddClientDisconnected()
                true
            }
            R.id.sim_custom_serial_script_clear -> {
                service!!.sim.customSerialScreen.clearWithDialog()
                true
            }
            R.id.sim_custom_serial_script_del -> {
                service!!.sim.customSerialScreen.onDelete()
                true
            }
            R.id.sim_custom_serial_script_dup -> {
                service!!.sim.customSerialScreen.onDuplicate()
                true
            }
            R.id.sim_custom_serial_script_start -> {
                service!!.sim.customSerialScreen.onStart()
                true
            }
            R.id.sim_custom_serial_script_stop -> {
                service!!.sim.customSerialScreen.onStop()
                true
            }
            R.id.logview_search -> {
                logController.view?.toggleTopBar()
                true
            }
            R.id.logview_download -> {
                logController.download()
                true
            }
            R.id.logview_download_as -> {
                logController.save()
                true
            }
            R.id.logview_clear -> {
                logController.clear()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // -------------------------------
    // Permissions
    // -------------------------------

    fun isPermissionsGranted(): Boolean {
        val bluetoothGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                (
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
                )

        return bluetoothGranted
    }

    fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissions = mutableListOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }

            requestPermissions(permissions.toTypedArray(), REQUEST_CODE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_CODE
            )
        }
    }

    // -------------------------------
    // Logic
    // -------------------------------

    fun requestNearbyWifiPermission(callback: (Boolean) -> Unit) {
        pendingWifiHotspotPermCallback = callback;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            nearbyWifiPermissionLauncher.launch(
                Manifest.permission.NEARBY_WIFI_DEVICES
            )
        } else {
            nearbyWifiPermissionLauncher.launch(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    fun showBluetoothEnablePopup() {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        enableBtLauncher.launch(intent)
    }

    fun appendLog(text: String, level: LogLevel = LogLevel.DEBUG) {
        logController.append(text, level)
    }

    override fun onDestroy() {
        MainActivityRef.activity = null
        super.onDestroy()
    }

}