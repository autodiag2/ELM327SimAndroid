package com.github.autodiag2.elm327emu.ui.settings

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
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
import com.github.autodiag2.elm327emu.ui.settings.generateQrBitmap
import com.github.autodiag2.elm327emu.R
import com.github.autodiag2.elm327emu.LogLevel
import com.github.autodiag2.elm327emu.LogLevel_DEFAULT
import com.github.autodiag2.elm327emu.MainActivity
import com.github.autodiag2.elm327emu.libautodiag
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.appcompat.widget.SwitchCompat
import androidx.core.widget.doAfterTextChanged
import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import androidx.core.app.ActivityCompat

private const val PREFS = "app_prefs"

private const val PREF_LOG_LEVEL = "log_level"
private const val PREF_LOG_GROUP = "log_exchange_dup_search"
private const val PREF_LOG_GROUP_SEARCH_N = "log_exchange_dup_search_depth"
private const val PREF_LOG_SIGNAL_VALUE = "log_signal_value"

private const val PREF_BT_NAME = "com_bt_name"

private const val NETWORK_BT = 0
private const val NETWORK_BLE = 1
private const val NETWORK_IP = 2

data class BleProfile(
    val name: String,
    val service: String,
    val tx: String,
    val rx: String,
    val isCustom: Boolean = false
)
val PREF_BLE_PROFILE = "com_ble_profile"

val PREF_BLE_SERVICE = "com_ble_service"
val PREF_BLE_TX = "com_ble_tx"
val PREF_BLE_RX = "com_ble_rx"

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

class SettingsFragment : PreferenceFragmentCompat() {

    private lateinit var activityMain: MainActivity
    private lateinit var prefs: android.content.SharedPreferences

    private var hotspotManager: LocalHotspotManager? = null

    private companion object {
        const val REQUEST_BLUETOOTH_CONNECT = 1001
    }

    override fun onResume() {
        super.onResume()

        val name = findPreference<androidx.preference.EditTextPreference>("com_bt_name")
            ?: return

        val enabled = activityMain.isPermissionsGranted()

        name.isEnabled = enabled

        if (enabled) {
            name.text = activityMain.btAdapter.getName() ?: ""
        }
    }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?
    ) {
        setPreferencesFromResource(R.layout.settings, rootKey)

        activityMain = requireActivity() as MainActivity
        prefs = activityMain.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        setupLog()
        setupNetwork()
        setupElm()
        setupBluetooth()
        setupBle()
        setupWifi()
        setupBtClassic()
    }

    private fun showPairedBluetoothDevices() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                REQUEST_BLUETOOTH_CONNECT
            )
            return
        }

        val view = layoutInflater.inflate(
            R.layout.settings_bt_classic_paired_devices,
            null
        )

        val list = view.findViewById<LinearLayout>(
            R.id.settings_bt_classic_paired_devices_list
        )

        val pairNew = view.findViewById<Button>(
            R.id.settings_bt_classic_pair_new
        )

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_bt_classic_paired_devices)
            .setView(view)
            .setNegativeButton(R.string.settings_bt_classic_close, null)
            .create()

        pairNew.setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                )
            } catch (_: Exception) {
            }
        }

        refreshPairedBluetoothDevices(list)

        dialog.show()
    }

    private fun refreshPairedBluetoothDevices(
        list: LinearLayout
    ) {

        list.removeAllViews()

        val adapter = activityMain.btAdapter

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val devices = adapter.bondedDevices
            .sortedBy { it.name ?: it.address }

        if (devices.isEmpty()) {

            val text = TextView(requireContext())

            text.text = getString(
                R.string.settings_bt_classic_no_paired_devices
            )

            list.addView(text)

            return
        }

        for (device in devices) {
            addBluetoothDeviceRow(list, device)
        }
    }

    private fun addBluetoothDeviceRow(
        list: LinearLayout,
        device: BluetoothDevice
    ) {

        val row = LinearLayout(requireContext())

        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, 8, 0, 8)

        val textContainer = LinearLayout(requireContext())

        textContainer.orientation = LinearLayout.VERTICAL

        val name = TextView(requireContext())

        name.text = device.name ?: device.address
        name.textSize = 16f

        val status = TextView(requireContext())

        status.text = bluetoothDeviceStatus(device)
        status.textSize = 12f

        textContainer.addView(name)
        textContainer.addView(status)

        val textParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        )

        row.addView(
            textContainer,
            textParams
        )

        val pairButton = Button(requireContext())

        pairButton.text = getString(
            R.string.settings_bt_classic_pair
        )

        pairButton.setOnClickListener {
            pairBluetoothDevice(device, status, pairButton)
        }

        /*
        * A bonded device normally doesn't need Pair.
        * Keep the button available so that a failed/removed bond
        * can be retried after the state changes.
        */
        pairButton.visibility =
            if (device.bondState == BluetoothDevice.BOND_BONDED)
                View.GONE
            else
                View.VISIBLE

        row.addView(pairButton)

        list.addView(row)
    }

    private fun bluetoothDeviceStatus(
        device: BluetoothDevice
    ): String {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return getString(R.string.settings_bt_classic_unknown)
        }

        return when (device.bondState) {

            BluetoothDevice.BOND_BONDED ->
                getString(R.string.settings_bt_classic_paired)

            BluetoothDevice.BOND_BONDING ->
                getString(R.string.settings_bt_classic_pairing)

            BluetoothDevice.BOND_NONE ->
                getString(R.string.settings_bt_classic_offline)

            else ->
                getString(R.string.settings_bt_classic_unknown)
        }
    }

    private fun pairBluetoothDevice(
        device: BluetoothDevice,
        status: TextView,
        button: Button
    ) {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        button.isEnabled = false

        status.text = getString(
            R.string.settings_bt_classic_pairing
        )

        try {

            if (!device.createBond()) {
                button.isEnabled = true

                status.text = getString(
                    R.string.settings_bt_classic_unknown
                )
            }

        } catch (e: Exception) {

            button.isEnabled = true

            status.text = e.message ?: getString(
                R.string.settings_bt_classic_unknown
            )
        }
    }

    private fun setupLog() {
        val logLevel = findPreference<ListPreference>(PREF_LOG_LEVEL)!!

        val values = LogLevel.values()

        logLevel.entries = values.map { it.name }.toTypedArray()
        logLevel.entryValues = values.map { it.ordinal.toString() }.toTypedArray()
        logLevel.value = prefs.getInt(
            PREF_LOG_LEVEL,
            LogLevel_DEFAULT.ordinal
        ).toString()

        logLevel.setOnPreferenceChangeListener { _, newValue ->
            val value = (newValue as String).toIntOrNull() ?: return@setOnPreferenceChangeListener false

            prefs.edit()
                .putInt(PREF_LOG_LEVEL, value)
                .apply()

            true
        }
        val timestamp = findPreference<SwitchPreferenceCompat>("log_timestamp")!!
        timestamp.isChecked = prefs.getBoolean("log_timestamp", false)
        timestamp.setOnPreferenceChangeListener { _, newValue ->
            prefs.edit()
                .putBoolean("log_timestamp", newValue as Boolean)
                .apply()

            true
        }
        val autostart = findPreference<SwitchPreferenceCompat>("app_emu_autostart")!!
        autostart.isChecked = prefs.getBoolean("app_emu_autostart", true)
        autostart.setOnPreferenceChangeListener { _, newValue ->
            prefs.edit()
                .putBoolean("app_emu_autostart", newValue as Boolean)
                .apply()

            true
        }
        val group = findPreference<SwitchPreferenceCompat>(PREF_LOG_GROUP)!!

        group.isChecked = prefs.getBoolean(PREF_LOG_GROUP, false)

        group.setOnPreferenceChangeListener { _, newValue ->
            prefs.edit()
                .putBoolean(PREF_LOG_GROUP, newValue as Boolean)
                .apply()

            true
        }

        val depth = findPreference<Preference>(PREF_LOG_GROUP_SEARCH_N)!!

        fun updateDepth() {
            depth.summary = prefs.getInt(
                PREF_LOG_GROUP_SEARCH_N,
                10
            ).toString()
        }

        updateDepth()

        depth.setOnPreferenceClickListener {
            val editText = EditText(requireContext())

            editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            editText.setText(
                prefs.getInt(
                    PREF_LOG_GROUP_SEARCH_N,
                    10
                ).toString()
            )

            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_log_exchange_dup_search_depth)
                .setView(editText)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val value = editText.text
                        .toString()
                        .toIntOrNull()
                        ?.coerceAtLeast(1)
                        ?: 1

                    prefs.edit()
                        .putInt(PREF_LOG_GROUP_SEARCH_N, value)
                        .apply()

                    updateDepth()
                }
                .show()

            true
        }

        val signal = findPreference<SwitchPreferenceCompat>(PREF_LOG_SIGNAL_VALUE)!!

        signal.isChecked = prefs.getBoolean(
            PREF_LOG_SIGNAL_VALUE,
            true
        )

        signal.setOnPreferenceChangeListener { _, newValue ->
            prefs.edit()
                .putBoolean(
                    PREF_LOG_SIGNAL_VALUE,
                    newValue as Boolean
                )
                .apply()

            true
        }
    }

    private fun setupNetwork() {
        val bridgePreferences = listOf(
            "com_nt_enabled",
            "com_ble_enabled",
            "com_bt_enabled"
        )

        for (key in bridgePreferences) {
            val preference = findPreference<SwitchPreferenceCompat>(key) ?: continue

            preference.isChecked = prefs.getBoolean(key, true)

            preference.setOnPreferenceChangeListener { _, newValue ->
                prefs.edit()
                    .putBoolean(key, newValue as Boolean)
                    .apply()

                when(key) {
                    "com_nt_enabled" -> activityMain.bridgeOrchestrator.setupNetworkBridge()
                    "com_ble_enabled" -> activityMain.bridgeOrchestrator.setupBleBridge()
                    "com_bt_enabled" -> activityMain.bridgeOrchestrator.setupBluetoothBridge()
                }

                true
            }
        }
    }

    private fun setupElm() {
        val protocol = findPreference<ListPreference>("protocol")!!

        val protocols = libautodiag.getProtocols()
        val currentProto = libautodiag.getProtocol()

        val offset = 1

        protocol.entries = protocols
        protocol.entryValues = (0 until protocols.size)
            .map { (it + offset).toString() }
            .toTypedArray()

        protocol.setValueIndex(currentProto - offset)

        val index = currentProto - offset

        if (index in protocols.indices) {
            protocol.value = (index + offset).toString()
        }

        protocol.setOnPreferenceChangeListener { _, newValue ->
            libautodiag.setProtocol(
                (newValue as String).toInt()
            )

            true
        }
    }

    private fun setupBluetooth() {
        val name = findPreference<EditTextPreference>(PREF_BT_NAME)!!

        val adapterName =
            if (activityMain.isPermissionsGranted()) {
                activityMain.btAdapter.getName() ?: ""
            } else {
                getString(R.string.settings_missing_permission)
            }

        name.text = adapterName
        name.isEnabled = activityMain.isPermissionsGranted()

        name.setOnPreferenceChangeListener { _, newValue ->
            val newName = (newValue as String).trim()

            if (newName.isEmpty()) {
                false
            } else {
                prefs.edit()
                    .putString(PREF_BT_NAME, newName)
                    .apply()
                activityMain.btAdapter.setName(newName)
                activityMain.bridgeOrchestrator.setupBleBridge()
                activityMain.bridgeOrchestrator.setupBluetoothBridge()

                true
            }
        }
    }

    private fun setupBle() {
        val profile = findPreference<ListPreference>("ble_profile")!!
        val custom = findPreference<Preference>("ble_custom")!!

        profile.entries = bleProfiles
            .map { it.name }
            .toTypedArray()

        profile.entryValues = bleProfiles
            .indices
            .map { it.toString() }
            .toTypedArray()

        val savedProfile = prefs.getInt(
            PREF_BLE_PROFILE,
            0
        )

        profile.value = savedProfile.toString()

        updateBleCustomSummary()

        profile.setOnPreferenceChangeListener { _, newValue ->
            val pos = (newValue as String).toInt()

            prefs.edit()
                .putInt(PREF_BLE_PROFILE, pos)
                .apply()

            val selected = bleProfiles[pos]


            prefs.edit()
                .putString(PREF_BLE_SERVICE, selected.service)
                .putString(PREF_BLE_TX, selected.tx)
                .putString(PREF_BLE_RX, selected.rx)
                .apply()

            activityMain.bridgeOrchestrator.setupBleBridge()
            updateBleCustomSummary()

            true
        }

        custom.setOnPreferenceClickListener {
            showBleCustomDialog()
            true
        }
    }

    private fun updateBleCustomSummary() {
        val custom = findPreference<Preference>("ble_custom")!!

        val service = prefs.getString(PREF_BLE_SERVICE, "") ?: ""
        val tx = prefs.getString(PREF_BLE_TX, "") ?: ""
        val rx = prefs.getString(PREF_BLE_RX, "") ?: ""

        custom.summary = listOf(
            service,
            tx,
            rx
        ).filter {
            it.isNotEmpty()
        }.joinToString("\n")
    }

    private fun showBleCustomDialog() {
        val container = layoutInflater.inflate(
            R.layout.dialog_ble_custom,
            null
        )

        val service = container.findViewById<EditText>(R.id.ble_service)
        val tx = container.findViewById<EditText>(R.id.ble_tx)
        val rx = container.findViewById<EditText>(R.id.ble_rx)

        service.setText(
            prefs.getString(PREF_BLE_SERVICE, "")
        )

        tx.setText(
            prefs.getString(PREF_BLE_TX, "")
        )

        rx.setText(
            prefs.getString(PREF_BLE_RX, "")
        )

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_ble_profiles_title)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.edit()
                    .putInt(
                        PREF_BLE_PROFILE,
                        bleProfiles.lastIndex
                    )
                    .putString(
                        PREF_BLE_SERVICE,
                        service.text.toString().trim()
                    )
                    .putString(
                        PREF_BLE_TX,
                        tx.text.toString().trim()
                    )
                    .putString(
                        PREF_BLE_RX,
                        rx.text.toString().trim()
                    )
                    .apply()

                findPreference<ListPreference>("ble_profile")!!
                    .value = bleProfiles.lastIndex.toString()

                updateBleCustomSummary()
                activityMain.bridgeOrchestrator.setupBleBridge()
            }
            .show()
    }

    private fun setupBtClassic() {
        findPreference<Preference>("bt_classic_ensure_device_paired")?.setOnPreferenceClickListener {
            showPairedBluetoothDevices()
            true
        }
    }

    private fun setupWifi() {
        val hotspot = findPreference<Preference>("wifi_hotspot")!!
        val qr = findPreference<WifiQrCodePreference>("wifi_qrcode")!!
        val ip_addr = findPreference<Preference>("wifi_ip_addr")!!

        hotspot.setOnPreferenceClickListener {
            if (hotspotManager == null) {
                hotspotManager = LocalHotspotManager(activityMain)
            }

            hotspotManager?.start(
                onStarted = { info ->
                    activityMain.appendLog(
                        getString(
                            R.string.log_wifi_hotspot_started,
                            info.ssid,
                            info.password
                        ),
                        LogLevel.INFO
                    )

                    hotspot.summary = getString(
                        R.string.log_wifi_hotspot_started,
                        info.ssid,
                        info.password
                    )

                    qr.setQrCode(info.wifiQr.length.let { len ->
                        if (len > 0) {
                            generateQrBitmap(info.wifiQr)
                        } else {
                            null
                        }
                    })

                },
                onFailed = { _, reasonStr ->
                    activityMain.appendLog(
                        reasonStr,
                        LogLevel.ERROR
                    )

                    hotspot.summary = getString(
                        R.string.settings_wifi_hotspot_summary_error,
                        reasonStr
                    )
                    qr.setQrCode(null)
                }
            )

            true
        }

        ip_addr.setOnPreferenceClickListener {
            activityMain.lifecycleScope.launch(Dispatchers.IO) {
                val result = LocalHotspotManager.findHotspotIp(true)

                withContext(Dispatchers.Main) {
                    ip_addr.summary = when (result) {
                        is LocalHotspotManager.HotspotIpResult.Success ->
                            getString(
                                R.string.settings_wifi_hotspot_gatewayIp,
                                result.ip
                            )

                        is LocalHotspotManager.HotspotIpResult.NoApInterface ->
                            getString(
                                R.string.settings_wifi_hotspot_gatewayIp_error,
                                getString(
                                    R.string.settings_wifi_hotspot_gatewayIp_error_no_ap_interface
                                )
                            )

                        is LocalHotspotManager.HotspotIpResult.MultipleApInterfaces ->
                            getString(
                                R.string.settings_wifi_hotspot_gatewayIp_error,
                                getString(
                                    R.string.settings_wifi_hotspot_gatewayIp_error_multiple_ap_interfaces,
                                    result.interfaces.joinToString(", ")
                                )
                            )

                        is LocalHotspotManager.HotspotIpResult.Exception ->
                            getString(
                                R.string.settings_wifi_hotspot_gatewayIp_error,
                                result.cause.message ?: "Unknown error"
                            )

                        is LocalHotspotManager.HotspotIpResult.NoRootInstalled ->
                            getString(
                                R.string.settings_wifi_hotspot_gatewayIp_error_no_root
                            )

                        is LocalHotspotManager.HotspotIpResult.RootPermissionDenied ->
                            getString(
                                R.string.settings_wifi_hotspot_gatewayIp_error_root_denied
                            )
                    }
                }
            }

            true
        }

    }
}