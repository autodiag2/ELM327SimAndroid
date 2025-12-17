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

private const val REQUEST_CODE = 1
private const val REQUEST_SAVE_LOG = 1001

class MainActivity : AppCompatActivity() {
    private val adapter = BluetoothAdapter.getDefaultAdapter()
    private val classicalBtUUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private val ELM_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private val ELM_RX_UUID      = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    private val ELM_TX_UUID      = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    private lateinit var gattServer: BluetoothGattServer
    private lateinit var advertiser: BluetoothLeAdvertiser
    private var connectedDevice: BluetoothDevice? = null
    private var txNotificationsEnabled = false
    private var gattReady = false

    private lateinit var rxChar: BluetoothGattCharacteristic
    private lateinit var txChar: BluetoothGattCharacteristic

    private var loopbackInput: InputStream? = null
    private var loopbackOutput: OutputStream? = null

    private var server: BluetoothServerSocket? = null
    private var socket: BluetoothSocket? = null
    private var bt_input: InputStream? = null
    private var bt_output: OutputStream? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    lateinit var contentFrame: FrameLayout
    private lateinit var drawer: DrawerLayout

    lateinit var simView: View
    private lateinit var dtcClearedCheck: CheckBox
    
    lateinit var logViewRoot: View
    private lateinit var logView: TextView
    lateinit var logScroll: ScrollView
    private var logPendingScroll = false
    lateinit var logButtonsContainer: LinearLayout
    lateinit var logFloatingButtons: LinearLayout
    private var logUserTouched = false

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
                    if (running) startBluetoothServer() else stopBluetoothServer()
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

    private fun buildLogView(): View {

        val logRoot = FrameLayout(this)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        fun buildButtons(): LinearLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(Button(this@MainActivity).apply {
                    text = "Download log"
                    setOnClickListener {
                        scope.launch {
                            val txt = logView.text.toString()
                            val file = File(getExternalFilesDir(null), "elm327emu_log.txt")
                            file.writeText(txt)
                            appendLog(LogLevel.INFO, "Log written to: ${file.absolutePath}")
                        }
                    }
                })
                addView(Button(this@MainActivity).apply {
                    text = "Download log on FS"
                    setOnClickListener { openSaveLogDialog() }
                })
                addView(Button(this@MainActivity).apply {
                    text = "Clear log"
                    setOnClickListener { logView.text = "" }
                })
            }

        logButtonsContainer = buildButtons()

        val buttonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(logButtonsContainer)
        }

        container.addView(
            buttonsRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 0
                bottomMargin = 0
                leftMargin = 0
                rightMargin = 0
            }
        )

        logView = TextView(this).apply {
            setPadding(16, 16, 16, 16)
        }
        container.addView(logView)

        logScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(container)
        }

        logScroll.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                logUserTouched = true
            }
            false
        }

        logScroll.viewTreeObserver.addOnScrollChangedListener {
            if (!logUserTouched) return@addOnScrollChangedListener

            val child = logScroll.getChildAt(0) ?: return@addOnScrollChangedListener
            val diff = child.bottom - (logScroll.height + logScroll.scrollY)

            if (diff == 0) {
                logUserTouched = false
            }
        }

        logFloatingButtons = buildButtons().apply {
            visibility = View.GONE
            elevation = dpToPx(6).toFloat()
        }

        logRoot.addView(
            logScroll,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER_VERTICAL or Gravity.END
            )
        )
        logRoot.addView(
            logFloatingButtons,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = 0
                marginEnd = 0
                leftMargin = 0
                rightMargin = 0
                bottomMargin = 0
            }
        )

        logScroll.viewTreeObserver.addOnScrollChangedListener {
            val triggerY = logButtonsContainer.top
            val scrolled = logScroll.scrollY > triggerY

            if (scrolled) {
                if (logFloatingButtons.visibility != View.VISIBLE) {
                    logFloatingButtons.visibility = View.VISIBLE
                    logButtonsContainer.visibility = View.INVISIBLE
                }
            } else {
                if (logFloatingButtons.visibility != View.GONE) {
                    logFloatingButtons.visibility = View.GONE
                    logButtonsContainer.visibility = View.VISIBLE
                }
            }
        }

        return logRoot
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
        val btNameLabel = TextView(this).apply {
            text = "Bluetooth device name"
            setPadding(0, 16, 0, 0)
        }
        root.addView(btNameLabel)

        val btNameEdit = EditText(this).apply {
            hint = "OBD II"
            setText(adapter?.name ?: "")
        }
        root.addView(btNameEdit)

        val applyBtn = Button(this).apply {
            text = "Set"
            setOnClickListener {
                val newName = btNameEdit.text.toString().trim()
                if (newName.isEmpty() || adapter == null) return@setOnClickListener

                if ( isPermissionsGranted() ) {
                    adapter.name = newName
                } else {
                    requestPermissions()
                }

            }
        }
        root.addView(applyBtn)

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
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            protocols
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        spinner.adapter = adapter

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
            scope.launch {
                contentResolver.openOutputStream(uri)?.use {
                    it.write(logView.text.toString().toByteArray())
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

    private fun showBluetoothEnablePopup() {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        startActivityForResult(intent, REQUEST_CODE)
    }

    private fun sendTx(device: BluetoothDevice, text: String) {
        if (!txNotificationsEnabled) return

        val bytes = text.toByteArray(Charsets.US_ASCII)
        val mtu = 20
        var i = 0

        while (i < bytes.size) {
            val end = minOf(i + mtu, bytes.size)
            txChar.value = bytes.copyOfRange(i, end)
            gattServer.notifyCharacteristicChanged(device, txChar, false)
            i = end
        }
    }

    private val gattCallback = object : BluetoothGattServerCallback() {

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            appendLog(LogLevel.DEBUG, "CCCD write: ${value.joinToString()}")
            if (descriptor.uuid.toString() == "00002902-0000-1000-8000-00805f9b34fb") {
                txNotificationsEnabled = value.contentEquals(byteArrayOf(0x01, 0x00))

                if (txNotificationsEnabled) {
                    sendTx(device, "ELM327 v1.5\r>")
                }
            }

            if (responseNeeded) {
                gattServer.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    null
                )
            }
        }

        override fun onConnectionStateChange(
            device: BluetoothDevice,
            status: Int,
            newState: Int
        ) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                appendLog(LogLevel.DEBUG, "connected")
                connectedDevice = device
            } else {
                appendLog(LogLevel.DEBUG, "disconnected")
                connectedDevice = null
            }
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                gattReady = true
                appendLog(LogLevel.DEBUG, "GATT service added")
            } else {
                appendLog(LogLevel.DEBUG, "Service add failed: $status")
            }
        }
        /*

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (responseNeeded) {
                gattServer.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    null
                )
            }
        }
        */

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            appendLog(LogLevel.DEBUG, "onCharacteristicWriteRequest")
            if (characteristic.uuid == ELM_RX_UUID) {
                loopbackOutput?.write(value)
                loopbackOutput?.flush()
            }

            if (responseNeeded) {
                gattServer.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    null
                )
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            appendLog(LogLevel.DEBUG, "BLE advertising started")
        }

        override fun onStartFailure(errorCode: Int) {
            appendLog(LogLevel.DEBUG, "BLE advertising failed: $errorCode")
        }
    }

    private fun dumpAdvertiseData(
        name: String?,
        serviceUuid: UUID?,
        includeFlags: Boolean = true
    ) {
        val bytes = ByteArrayOutputStream()

        if (includeFlags) {
            bytes.write(byteArrayOf(
                0x02,
                0x01,
                0x06
            ))
        }

        if (name != null) {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            bytes.write(nameBytes.size + 1)
            bytes.write(0x09)
            bytes.write(nameBytes)
        }

        if (serviceUuid != null) {
            val uuidBytes = ByteBuffer
                .allocate(16)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putLong(serviceUuid.leastSignificantBits)
                .putLong(serviceUuid.mostSignificantBits)
                .array()

            bytes.write(17)
            bytes.write(0x07)
            bytes.write(uuidBytes)
        }

        val payload = bytes.toByteArray()
        appendLog(LogLevel.DEBUG, "ADV length = ${payload.size}")
        appendLog(LogLevel.DEBUG, payload.joinToString(" ") { "%02X".format(it) })
    }

    private fun startBluetoothServer() {
        if (!adapter.isEnabled) {
            showBluetoothEnablePopup()
            return
        }

        scope.launch(Dispatchers.IO) {
            clearSocketFiles()

            appendLog(LogLevel.DEBUG, "A")
            advertiser = adapter.bluetoothLeAdvertiser
            appendLog(LogLevel.DEBUG, "A")
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build()
            appendLog(LogLevel.DEBUG, "A")
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceUuid(ParcelUuid(ELM_SERVICE_UUID))
                .build()
            val advData = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build()
            appendLog(LogLevel.DEBUG, "data.toString():")
            appendLog(LogLevel.DEBUG, data.toString())
            if (!adapter.isMultipleAdvertisementSupported) {
                appendLog(LogLevel.DEBUG, "BLE advertising not supported")
                return@launch
            }

            advertiser = adapter.bluetoothLeAdvertiser ?: run {
                appendLog(LogLevel.DEBUG, "bluetoothLeAdvertiser == null")
                return@launch
            }
            dumpAdvertiseData(
                name = adapter.name,
                serviceUuid = ELM_SERVICE_UUID
            )
            val scanResp = AdvertiseData.Builder()
                .addServiceUuid(ParcelUuid(ELM_SERVICE_UUID))
                .build()

            appendLog(LogLevel.DEBUG, "A")
            val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            gattServer = btManager.openGattServer(this@MainActivity, gattCallback)
            appendLog(LogLevel.DEBUG, "A")
            val service = BluetoothGattService(
                ELM_SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )
            appendLog(LogLevel.DEBUG, "A")

            rxChar = BluetoothGattCharacteristic(
                ELM_RX_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            appendLog(LogLevel.DEBUG, "A")

            txChar = BluetoothGattCharacteristic(
                ELM_TX_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            appendLog(LogLevel.DEBUG, "A")

            val cccd = BluetoothGattDescriptor(
                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
            appendLog(LogLevel.DEBUG, "A")

            txChar.addDescriptor(cccd)
            service.addCharacteristic(rxChar)
            service.addCharacteristic(txChar)

            gattServer.addService(service)
            while (!gattReady) {
                delay(10)
            }
            advertiser.startAdvertising(settings, advData, scanResp, advertiseCallback)

            appendLog(LogLevel.DEBUG, "A")
            val location = libautodiag.launchEmu(filesDir.absolutePath)
            appendLog(LogLevel.DEBUG, "A")
            val loopSock = LocalSocket()
            loopSock.connect(LocalSocketAddress(location, LocalSocketAddress.Namespace.FILESYSTEM))
            loopbackInput = loopSock.inputStream
            loopbackOutput = loopSock.outputStream
            appendLog(LogLevel.DEBUG, "A")
            launch {
                val buffer = ByteArray(512)
                while (isActive) {
                    appendLog(LogLevel.DEBUG, "waiting a read")
                    val n = loopbackInput?.read(buffer) ?: break
                    if (n <= 0) break
                    txChar.value = buffer.copyOf(n)
                    appendLog(LogLevel.DEBUG, "readed values")
                    connectedDevice?.let {
                        gattServer.notifyCharacteristicChanged(it, txChar, false)
                    }
                }
            }
        }
    }


    private fun appendLog(level: LogLevel, text: String) {

        if (!::logView.isInitialized) return

        runOnUiThread {
            logView.append(text + "\n")
            if (autoScroll && !logPendingScroll && !logUserTouched) {
                logPendingScroll = true
                logScroll.post {
                    logScroll.fullScroll(View.FOCUS_DOWN)
                    logPendingScroll = false
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