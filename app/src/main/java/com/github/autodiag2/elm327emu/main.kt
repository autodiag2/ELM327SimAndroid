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
        toolbar.setNavigationIcon(android.R.drawable.ic_menu_sort_by_size)
    }

    fun showBackArrow() {
        toolbar.setNavigationIcon(android.R.drawable.ic_media_previous)
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

    private fun show(view: View) {
        contentFrame.removeAllViews()
        contentFrame.addView(view)
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
        appendLog("Bluetooth server stopped", LogLevel.INFO)
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
            else -> appendLog("Network mode not implemented", LogLevel.DEBUG)
        }
    }

    fun onDataReceived(data: ByteArray, size_used: Int) {
        appendLog("recv : \n" + logView.dataLogFormat(data, size_used), LogLevel.DEBUG)
        statsView.onDataReceived(data, size_used)
    }

    fun onDataSent(data: ByteArray, size_used: Int) {
        appendLog("send : \n" + logView.dataLogFormat(data, size_used), LogLevel.DEBUG)
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