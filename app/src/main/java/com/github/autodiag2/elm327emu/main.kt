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
import android.content.ClipData
import android.content.ClipboardManager
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
import org.luaj.vm2.*
import org.luaj.vm2.lib.jse.*

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
    lateinit var toggle: ActionBarDrawerToggle
    lateinit var toolbar: Toolbar

    lateinit var dtcClearedCheck: CheckBox

    lateinit var simView: SimView
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
            handleBack()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    fun isPermissionsGranted(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) &&
                (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED)
        } else {
            return true
        }
    }

    fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE), REQUEST_CODE)
        }
    }

    fun handleBack() {
        if (screenStack.isNotEmpty()) {
            show(screenStack.removeLast())

            showHamburger()
        } else {
            showHamburger()
            drawer.openDrawer(Gravity.LEFT)
        }
    }

    fun showHamburger() {
        toggle.isDrawerIndicatorEnabled = true
        toggle.syncState()
    }

    fun showBackArrow() {
        toggle.isDrawerIndicatorEnabled = false
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener {
            handleBack()
        }
        toggle.syncState()
    }

    fun openEcuConfig(ecu: EcuConfig) {
        screenStack.addLast(simView)
        show(ecu.screen)
        showBackArrow()
    }

    public lateinit var logRepo: LogRepository

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
                    if ( screenStack.isNotEmpty() ) {
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
        logRepo = LogRepository(this)

        // ---- Bind views from XML ----
        drawer = findViewById(R.id.drawer)
        contentFrame = findViewById(R.id.contentFrame)
        val navView: NavigationView = findViewById(R.id.navView)
        toolbar = findViewById(R.id.toolbar)

        // ---- Toolbar setup ----
        setSupportActionBar(toolbar)
        val toggleLocal = androidx.appcompat.app.ActionBarDrawerToggle(
            this,
            drawer,
            toolbar,
            R.string.open,
            R.string.close
        )
        toggle = toggleLocal
        drawer.addDrawerListener(toggleLocal)
        toggleLocal.syncState()

        simView = SimView(this)
        logView = LogView(this)
        settingsView = SettingsView(this)
        statsView = StatsView(this)

        // Default screen
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

    fun stopServer() {

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

    fun startServer() {
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