package com.github.autodiag2.elm327emu

import android.content.Context
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner

class SettingsView(
    private val activity: MainActivity
) : View(activity) {
    val root: View = activity.layoutInflater.inflate(R.layout.settings, activity.contentFrame, false)
    
    init {
        setup()
    }
    
    private fun setup() {
        val prefs = activity.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

        val btContainer = root.findViewById<LinearLayout>(R.id.btContainer)
        val btNameEdit = root.findViewById<EditText>(R.id.btNameEdit)
        val btApplyBtn = root.findViewById<Button>(R.id.btApplyBtn)

        val logLevelSpinner = root.findViewById<Spinner>(R.id.logLevelSpinner)

        val networkSpinner = root.findViewById<Spinner>(R.id.networkSpinner)
        val protocolSpinner = root.findViewById<Spinner>(R.id.protocolSpinner)

        // ---------- Bluetooth ----------
        val adapterName = if (activity.isPermissionsGranted()) activity.btAdapter.name ?: "" else "Missing permission"
        btNameEdit.setText(adapterName)

        btApplyBtn.setOnClickListener {
            val newName = btNameEdit.text.toString().trim()
            if (newName.isNotEmpty()) {
                if (activity.isPermissionsGranted()) activity.btAdapter.name = newName
                else activity.requestPermissions()
            }
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

        val savedLogLevel = prefs.getInt("log_level", LogLevel.INFO.ordinal)
        logLevelSpinner.setSelection(savedLogLevel)

        logLevelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                prefs.edit().putInt("log_level", pos).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // ---------- Network ----------
        val networks = listOf("Bluetooth", "Bluetooth LE (4.0+)", "Network")

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

        networkSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                prefs.edit().putInt("network_mode", pos).apply()

                btContainer.visibility =
                    if (pos == activity.NETWORK_BT || pos == activity.NETWORK_BLE) VISIBLE else GONE
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