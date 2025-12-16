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
import com.autodiag.elm327emu.SettingsActivity
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
import android.view.ViewGroup.LayoutParams

private const val REQUEST_CODE = 1

class MainActivity : AppCompatActivity() {
    private val adapter = BluetoothAdapter.getDefaultAdapter()
    private val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var server: BluetoothServerSocket? = null
    private var socket: BluetoothSocket? = null
    private var bt_input: InputStream? = null
    private var bt_output: OutputStream? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var drawer: DrawerLayout
    private lateinit var logView: TextView
    private lateinit var dtcClearedCheck: CheckBox

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
            return (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
        } else {
            return true
        }
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_CODE)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MainActivityRef.activity = this
        
        drawer = DrawerLayout(this)

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
                    if (running) startBluetoothServer() else stopBluetoothServer()
                } else {
                    requestPermissions()
                }
            }
        }

        container.addView(startStop)

        val downloadLog = Button(this).apply {
            text = "Download log"
            setOnClickListener {
                scope.launch {
                    val txt = if (::logView.isInitialized) logView.text.toString() else ""
                    val file = File(getExternalFilesDir(null), "elm327emu_log.txt")
                    file.writeText(txt)
                    appendLog(LogLevel.INFO, "Log written to: ${file.absolutePath}")
                }
            }
        }

        container.addView(downloadLog)

        if (BuildConfig.DEBUG) {

            val clearLog = Button(this).apply {
                text = "Clear log"
                setOnClickListener {
                    runOnUiThread { logView.text = "" }
                }
            }

            container.addView(clearLog)

            logView = TextView(this).apply {
                setPadding(16, 16, 16, 16)
                movementMethod = ScrollingMovementMethod()
            }

            container.addView(logView)
        }

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            addView(
                container,
                android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val content = FrameLayout(this).apply {
            addView(
                scrollView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        val navView = NavigationView(this).apply {
            menu.add("Settings").setOnMenuItemClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                drawer.closeDrawer(Gravity.LEFT)
                true
            }
        }

        drawer.addView(
            content,
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

    fun setDtcClearedUi(value: Boolean) {
        dtcClearedCheck.isChecked = value
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun stopBluetoothServer() {
        try {
            bt_input?.close()
            bt_input = null

            bt_output?.close()
            bt_output = null

            socket?.close()
            socket = null

            server?.close()
            server = null
        } catch (_: Exception) {
        }

        scope.coroutineContext.cancelChildren()

        appendLog(LogLevel.INFO, "Bluetooth server stopped")
    }
    
    private fun clearSocketFiles() {
        val dir = filesDir
        dir.listFiles()?.forEach { f ->
            if (f.name.startsWith("socket")) {
                f.delete()
            }
        }
    }

    private fun startBluetoothServer() {
        scope.launch {
            clearSocketFiles()
            while (true) {
                try {
                    server = adapter.listenUsingRfcommWithServiceRecord("BTSerial", uuid)
                    appendLog(LogLevel.INFO, "Waiting for connection...")

                    socket = server?.accept()
                    appendLog(LogLevel.INFO, "Client connected: ${socket?.remoteDevice?.address}")

                    bt_input = socket?.inputStream
                    bt_output = socket?.outputStream

                    val filesDirPath = filesDir.absolutePath
                    val location = libautodiag.launchEmu(filesDirPath)
                    appendLog(LogLevel.DEBUG, "Native sim location: $location")

                    val loopbackSocket = LocalSocket()
                    loopbackSocket.connect(
                        LocalSocketAddress(location, LocalSocketAddress.Namespace.FILESYSTEM)
                    )

                    appendLog(LogLevel.DEBUG, "Loopback socket connected")

                    val loopbackInput = loopbackSocket.inputStream
                    val loopbackOutput = loopbackSocket.outputStream

                    val bufferBT = ByteArray(1024)
                    val bufferLoop = ByteArray(1024)

                    val btToLoop = launch {
                        while (isActive) {
                            try {
                                val n = bt_input?.read(bufferBT) ?: break
                                if (n <= 0) break
                                loopbackOutput.write(bufferBT, 0, n)
                                loopbackOutput.flush()
                                appendLog(LogLevel.DEBUG, " * Received from Bluetooth: (passing to loopback)")
                                appendLog(LogLevel.DEBUG, hexDump(bufferBT, n))
                            } catch(e: Exception) {
                                appendLog(LogLevel.DEBUG, "exiting btToLoop: ${e.message}")
                                break
                            }
                        }
                    }

                    val loopToBt = launch {
                        while (isActive) {
                            try {
                                val n = loopbackInput?.read(bufferLoop) ?: break
                                if (n <= 0) break
                                bt_output?.write(bufferLoop, 0, n)
                                bt_output?.flush()
                                appendLog(LogLevel.DEBUG, " * Sending the data received from loopback on bluetooth:")
                                appendLog(LogLevel.DEBUG, hexDump(bufferLoop, n))
                            } catch(e: Exception) {
                                appendLog(LogLevel.DEBUG, "exiting loopToBt: ${e.message}")
                                break
                            }
                        }
                    }

                    btToLoop.join()
                    loopToBt.cancel()

                    loopbackInput.close()
                    loopbackOutput.close()
                    loopbackSocket.close()
                } catch (e: CancellationException) {
                    appendLog(LogLevel.DEBUG, "Cancelled")
                    throw e
                } catch (e: Exception) {
                    appendLog(LogLevel.DEBUG, "Error: ${e.message}")
                } finally {
                    bt_input?.close()
                    bt_output?.close()
                    socket?.close()
                    server?.close()
                    appendLog(LogLevel.INFO, "Bluetooth connection closed")
                }
            }
        }
    }

    private fun appendLog(level: LogLevel, text: String) {
        var shouldLog = false;
        if ( BuildConfig.DEBUG ) {
            shouldLog = true;
        } else {
            if ( level == LogLevel.INFO ) {
                shouldLog = true;
            }
        }
        if ( shouldLog ) {
            runOnUiThread {
                logView.append(text + "\n")
                if ( autoScroll ) {
                    val layout = logView.layout
                    if (layout != null) {
                        val scroll = layout.getLineTop(logView.lineCount) - logView.height
                        if (0 < scroll) logView.scrollTo(0, scroll) else logView.scrollTo(0, 0)
                    }
                }
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