package com.github.autodiag2.elm327emu

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ImageView
import com.github.autodiag2.elm327emu.com.LocalHotspotManager
import com.github.autodiag2.elm327emu.generateQrBitmap
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BleProfile(
    val name: String,
    val service: String,
    val tx: String,
    val rx: String,
    val isCustom: Boolean = false
)
val PREF_BLE_PROFILE = "ble_profile"

val PREF_BLE_SERVICE = "ble_service"
val PREF_BLE_TX = "ble_tx"
val PREF_BLE_RX = "ble_rx"

val bleProfiles = listOf(
    BleProfile(
        "Nordic UART Service",
        "6E400001-B5A3-F393-E0A9-E50E24DCCA9E",
        "6E400003-B5A3-F393-E0A9-E50E24DCCA9E",
        "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
    ),
    BleProfile(
        "Microchip Transparent UART Service",
        "49535343-FE7D-4AE5-8FA9-9FAFD205E455",
        "49535343-1E4D-4BD9-BA61-23C647249616",
        "49535343-8841-43F4-A8D4-ECBE34729BB3"
    ),
    BleProfile(
        "Nexas",
        "0000fff0-0000-1000-8000-00805f9b34fb",
        "0000fff1-0000-1000-8000-00805f9b34fb",
        "0000fff2-0000-1000-8000-00805f9b34fb"
    ),
    BleProfile(
        "Nexas (less common)",
        "000018f0-0000-1000-8000-00805f9b34fb",
        "00002af0-0000-1000-8000-00805f9b34fb",
        "00002af1-0000-1000-8000-00805f9b34fb"
    ),
    BleProfile(
        "Custom",
        "",
        "",
        "",
        isCustom = true
    )
)

class SettingsView(
    private val activity: MainActivity
) : FrameLayout(activity) {
    
    init {
        LayoutInflater.from(context).inflate(R.layout.settings, this, true)
        setup()
    }
    
    fun getString(resId: Int, vararg formatArgs: Any?): String {
        return activity.getString(resId, *formatArgs.map { it ?: "" }.toTypedArray())
    }

    private var hotspotManager: LocalHotspotManager? = null

    private fun setup() {
        val prefs = activity.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

        val wifiSettingsContainer = findViewById<LinearLayout>(R.id.wifiContainer)
        val wifiApLaunchBtn = findViewById<Button>(R.id.settings_wifi_startBtn)
        val wifiApErrorReturn = findViewById<TextView>(R.id.settings_wifi_startBtn_hotspot_error_return)
        val wifiApQrCode = findViewById<ImageView>(R.id.settings_wifi_qrCode)
        val wifiApGatewayIp = findViewById<TextView>(R.id.settings_wifi_hotspot_gatewayIp)
        val wifiApGatewayIpBtn = findViewById<Button>(R.id.settings_wifi_hotspot_gatewayIpBtn)
        val btContainer = findViewById<LinearLayout>(R.id.btContainer)
        val btNameEdit = findViewById<EditText>(R.id.btNameEdit)
        val btApplyBtn = findViewById<Button>(R.id.btApplyBtn)

        val logLevelSpinner = findViewById<Spinner>(R.id.logLevelSpinner)

        val networkSpinner = findViewById<Spinner>(R.id.networkSpinner)
        val protocolSpinner = findViewById<Spinner>(R.id.protocolSpinner)

        val bleConfigContainer = findViewById<LinearLayout>(R.id.bleConfigContainer)
        val bleProfileSpinner = findViewById<Spinner>(R.id.bleProfileSpinner)
        val customBleContainer = findViewById<LinearLayout>(R.id.customBleContainer)

        val customServiceUuid = findViewById<EditText>(R.id.customServiceUuid)
        val customTxUuid = findViewById<EditText>(R.id.customTxUuid)
        val customRxUuid = findViewById<EditText>(R.id.customRxUuid)

        wifiApQrCode.visibility = View.GONE
        
        // ---------- Wi-Fi ----------
        wifiApGatewayIpBtn.setOnClickListener {
            activity.lifecycleScope.launch(Dispatchers.IO) {
                val result = hotspotManager?.findHotspotIpRoot()

                withContext(Dispatchers.Main) {
                    when (result) {
                        is LocalHotspotManager.HotspotIpResult.Success -> {
                            val ip = result.ip
                            wifiApGatewayIp.text = getString(R.string.settings_wifi_hotspot_gatewayIp, ip)
                        }
                        is LocalHotspotManager.HotspotIpResult.NoApInterface -> {
                            wifiApGatewayIp.text = getString(R.string.settings_wifi_hotspot_gatewayIp_error, getString(R.string.settings_wifi_hotspot_gatewayIp_error_no_ap_interface))
                        }
                        is LocalHotspotManager.HotspotIpResult.MultipleApInterfaces -> {
                            wifiApGatewayIp.text = getString(R.string.settings_wifi_hotspot_gatewayIp_error, getString(R.string.settings_wifi_hotspot_gatewayIp_error_multiple_ap_interfaces, result.interfaces.joinToString(", ")))
                        }
                        is LocalHotspotManager.HotspotIpResult.Exception -> {
                            wifiApGatewayIp.text = getString(R.string.settings_wifi_hotspot_gatewayIp_error, result.cause.message ?: "Unknown error")
                        }
                        is LocalHotspotManager.HotspotIpResult.NoRootInstalled -> {
                            wifiApGatewayIp.text = getString(R.string.settings_wifi_hotspot_gatewayIp_error_no_root)
                        }
                        is LocalHotspotManager.HotspotIpResult.RootPermissionDenied -> {
                            wifiApGatewayIp.text = getString(R.string.settings_wifi_hotspot_gatewayIp_error_root_denied)
                        }
                        else -> {
                            wifiApGatewayIp.text = getString(R.string.settings_wifi_hotspot_gatewayIp_error, "Hotspot manager not initialized")
                        }
                    }
                }
            }
        }
        wifiApLaunchBtn.setOnClickListener {
            if (hotspotManager == null) {
                hotspotManager = LocalHotspotManager(activity)
            }

            hotspotManager?.start(
                onStarted = { info ->
                    activity.appendLog(getString(R.string.log_wifi_hotspot_started, info.ssid, info.password), LogLevel.INFO)
                    wifiApErrorReturn.text = getString(R.string.log_wifi_hotspot_started, info.ssid, info.password)
                    val qrBitmap = generateQrBitmap(info.wifiQr, 300)
                    wifiApQrCode.setImageBitmap(qrBitmap)
                    wifiApQrCode.visibility = View.VISIBLE
                    activity.serverRestartWithUI()
                },
                onFailed = { reason, reasonStr ->
                    activity.appendLog(reasonStr, LogLevel.ERROR)
                    wifiApErrorReturn.text = reasonStr
                    wifiApQrCode.visibility = View.GONE
                }
            )
        }
        // ---------- Bluetooth ----------
        val adapterName = if (activity.isPermissionsGranted()) activity.btAdapter.name ?: "" else getString(R.string.settings_missing_permission)
        btNameEdit.setText(adapterName)

        btApplyBtn.setOnClickListener {
            val newName = btNameEdit.text.toString().trim()
            if (newName.isNotEmpty()) {
                if (activity.isPermissionsGranted()) activity.btAdapter.name = newName
                else activity.requestPermissions()
            }
        }
        bleProfileSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {

                override fun onItemSelected(
                    parent: AdapterView<*>,
                    view: View?,
                    pos: Int,
                    id: Long
                ) {

                    prefs.edit()
                        .putInt(PREF_BLE_PROFILE, pos)
                        .apply()

                    val profile = bleProfiles[pos]

                    if ( ! profile.isCustom) {

                        prefs.edit()
                            .putString(PREF_BLE_SERVICE, profile.service)
                            .putString(PREF_BLE_TX, profile.tx)
                            .putString(PREF_BLE_RX, profile.rx)
                            .apply()

                        customServiceUuid.setText(profile.service)
                        customTxUuid.setText(profile.tx)
                        customRxUuid.setText(profile.rx)
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>) {}
            }

        bleProfileSpinner.adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_item,
            bleProfiles.map { it.name }
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val savedBleProfile = prefs.getInt(PREF_BLE_PROFILE, 0)
        bleProfileSpinner.setSelection(savedBleProfile)
        customServiceUuid.setText(
            prefs.getString(PREF_BLE_SERVICE, "")
        )

        customTxUuid.setText(
            prefs.getString(PREF_BLE_TX, "")
        )

        customRxUuid.setText(
            prefs.getString(PREF_BLE_RX, "")
        )

        fun saveCustomBleConfig() {
            prefs.edit()
                .putString(
                    PREF_BLE_SERVICE,
                    customServiceUuid.text.toString().trim()
                )
                .putString(
                    PREF_BLE_TX,
                    customTxUuid.text.toString().trim()
                )
                .putString(
                    PREF_BLE_RX,
                    customRxUuid.text.toString().trim()
                )
                .apply()
        }

        customServiceUuid.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveCustomBleConfig()
        }

        customTxUuid.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveCustomBleConfig()
        }

        customRxUuid.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveCustomBleConfig()
        }

        // ---------- Log level ----------
        val logLevels = LogLevel.values().toList()

        logLevelSpinner.adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_item,
            logLevels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        val savedLogLevel = prefs.getInt("log_level", LogLevel_DEFAULT.ordinal)
        logLevelSpinner.setSelection(savedLogLevel)

        logLevelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                prefs.edit().putInt("log_level", pos).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // ---------- Network ----------
        val networks = listOf(
            getString(R.string.settings_network_bluetooth),
            getString(R.string.settings_network_ble),
            getString(R.string.settings_network_network)
        )

        networkSpinner.adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_item,
            networks
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        val savedNetwork = prefs.getInt("network_mode", activity.NETWORK_BT)
        networkSpinner.setSelection(savedNetwork)

        btContainer.visibility =
            if (savedNetwork == activity.NETWORK_BT || savedNetwork == activity.NETWORK_BLE) VISIBLE else GONE
        
        wifiSettingsContainer.visibility =
            if (savedNetwork == activity.NETWORK_IP) VISIBLE else GONE

        networkSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                prefs.edit().putInt("network_mode", pos).apply()

                btContainer.visibility =
                    if (pos == activity.NETWORK_BT || pos == activity.NETWORK_BLE) VISIBLE else GONE
                bleConfigContainer.visibility =
                    if (pos == activity.NETWORK_BLE) VISIBLE else GONE
                wifiSettingsContainer.visibility =
                    if (pos == activity.NETWORK_IP) VISIBLE else GONE
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // ---------- ELM ----------
        val protocols = libautodiag.getProtocols()
        val currentProto = libautodiag.getProtocol()
        val offset = 1

        protocolSpinner.adapter = ArrayAdapter(
            activity,
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
    }

}