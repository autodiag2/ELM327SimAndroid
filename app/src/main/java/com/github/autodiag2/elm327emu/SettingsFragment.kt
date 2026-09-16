package com.github.autodiag2.elm327emu

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
import com.github.autodiag2.elm327emu.generateQrBitmap
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.appcompat.widget.SwitchCompat
import androidx.core.widget.doAfterTextChanged

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

    override fun onResume() {
        super.onResume()

        val name = findPreference<androidx.preference.EditTextPreference>("com_bt_name")
            ?: return

        val enabled = activityMain.isPermissionsGranted()

        name.isEnabled = enabled

        if (enabled) {
            name.text = activityMain.btAdapter.name ?: ""
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

                activityMain.bridgeOrchestrator.setupBridges()

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
                activityMain.btAdapter.name ?: ""
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

            if (!selected.isCustom) {
                prefs.edit()
                    .putString(PREF_BLE_SERVICE, selected.service)
                    .putString(PREF_BLE_TX, selected.tx)
                    .putString(PREF_BLE_RX, selected.rx)
                    .apply()

                activityMain.bridgeOrchestrator.setupBridges()
            }

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
        val container = android.widget.LinearLayout(requireContext())

        container.orientation = android.widget.LinearLayout.VERTICAL
        container.setPadding(
            48,
            0,
            48,
            0
        )

        val service = EditText(requireContext())
        val tx = EditText(requireContext())
        val rx = EditText(requireContext())

        service.hint = "Service UUID"
        tx.hint = "TX UUID"
        rx.hint = "RX UUID"

        service.setText(
            prefs.getString(PREF_BLE_SERVICE, "")
        )

        tx.setText(
            prefs.getString(PREF_BLE_TX, "")
        )

        rx.setText(
            prefs.getString(PREF_BLE_RX, "")
        )

        service.inputType =
            android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS

        tx.inputType =
            android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS

        rx.inputType =
            android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS

        container.addView(service)
        container.addView(tx)
        container.addView(rx)

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
                activityMain.bridgeOrchestrator.setupBridges()
            }
            .show()
    }

    private fun setupWifi() {
        val hotspot = findPreference<Preference>("wifi_hotspot")!!
        val qr = findPreference<Preference>("wifi_qrcode")!!
        val gateway = findPreference<Preference>("wifi_gateway")!!

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

                    qr.summary = getString(
                        R.string.log_wifi_hotspot_started,
                        info.ssid,
                        info.password
                    )

                    qr.isVisible = true
                },
                onFailed = { _, reasonStr ->
                    activityMain.appendLog(
                        reasonStr,
                        LogLevel.ERROR
                    )

                    qr.summary = reasonStr
                    qr.isVisible = true
                }
            )

            true
        }

        gateway.setOnPreferenceClickListener {
            activityMain.lifecycleScope.launch(Dispatchers.IO) {
                val result = hotspotManager?.findHotspotIp(true)

                withContext(Dispatchers.Main) {
                    gateway.summary = when (result) {
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

                        else ->
                            getString(
                                R.string.settings_wifi_hotspot_gatewayIp_error,
                                "Hotspot manager not initialized"
                            )
                    }
                }
            }

            true
        }

        qr.isVisible = false
    }
}