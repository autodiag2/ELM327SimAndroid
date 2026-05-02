package com.github.autodiag2.elm327emu

import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.core.widget.addTextChangedListener

fun buildEcuGuiConfig(address: Int, name: String, activity: MainActivity): EcuConfig {
    val view = activity.layoutInflater.inflate(R.layout.sim_main_ecu_config_gui, activity.contentFrame, false)
    val allSignals = libautodiag.getSimSignals().sortedBy { it.name.lowercase() }
    val addedSignalPaths = linkedSetOf<String>()
    val dynamicSignalsContainer = view.findViewById<LinearLayout>(R.id.signal_container)

    fun getSignalInitialValue(signal: SimSignal): Double {
        val v = libautodiag.getSignalValue(signal.path)
        if (!v.isNaN()) {
            return v
        }
        return signal.min
    }

    fun setSignalValue(signal: SimSignal, value: Double) {
        SimGeneratorGui.setSignalValue(signal.path, value)
    }

    fun addSignalWidget(signal: SimSignal) {
        if (addedSignalPaths.contains(signal.path)) {
            return
        }
        addedSignalPaths.add(signal.path)

        val title = TextView(activity).apply {
            text = if (signal.unit.isNullOrBlank()) signal.name else "${signal.name} (${signal.unit})"
        }

        val step = if (signal.step <= 0.0) 1.0 else signal.step
        val scale = (1.0 / step).toInt().coerceAtLeast(1)
        val minI = (signal.min * scale).toInt()
        val maxI = (signal.max * scale).toInt()
        val initialI = (((getSignalInitialValue(signal)).coerceIn(signal.min, signal.max)) * scale).toInt()

        val valueText = TextView(activity).apply {
            val shown = initialI.toDouble() / scale
            text = if (signal.unit.isNullOrBlank()) "$shown" else "$shown ${signal.unit}"
        }

        val seek = SeekBar(activity).apply {
            max = (maxI - minI).coerceAtLeast(0)
            progress = (initialI - minI).coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    val v = (p + minI).toDouble() / scale
                    valueText.text = if (signal.unit.isNullOrBlank()) "$v" else "$v ${signal.unit}"
                    setSignalValue(signal, v)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }

        setSignalValue(signal, initialI.toDouble() / scale)

        val removeBtn = Button(activity).apply {
            text = "Remove"
        }

        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(removeBtn)
        }

        val block = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12, 0, 12)
            addView(headerRow)
            addView(valueText)
            addView(seek)
        }

        removeBtn.setOnClickListener {
            addedSignalPaths.remove(signal.path)
            dynamicSignalsContainer.removeView(block)
        }

        dynamicSignalsContainer.addView(block)
    }

    val signalSpinner = view.findViewById<Spinner>(R.id.signal_choice)
    val spinnerSignals = allSignals
    val spinnerAdapter = ArrayAdapter(
        activity,
        android.R.layout.simple_spinner_item,
        spinnerSignals.map { it.name }
    ).apply {
        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }
    signalSpinner.adapter = spinnerAdapter

    view.findViewById<Button>(R.id.signal_add).apply {
        setOnClickListener {
            val index = signalSpinner.selectedItemPosition

            if (index in spinnerSignals.indices) {
                val signal = spinnerSignals[index]
                addSignalWidget(signal)
            }
        }
    }

    allSignals.firstOrNull { it.path == "SAEJ1979.engine_speed" }?.let { addSignalWidget(it) }
    allSignals.firstOrNull { it.path == "SAEJ1979.vehicle_speed" }?.let { addSignalWidget(it) }
    allSignals.firstOrNull { it.path == "SAEJ1979.coolant_temp" }?.let { addSignalWidget(it) }

    val dtcs = mutableListOf<String>()
    val dtcContainer = view.findViewById<LinearLayout>(R.id.dtc_list)

    fun addDtcRow(code: String) {
        if (code.isBlank()) return

        dtcs.add(code)
        SimGeneratorGui.dtcs.add(code)

        val text = TextView(activity).apply {
            text = code
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val removeBtn = Button(activity)
        removeBtn.text = "X"

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
            addView(text)
            addView(removeBtn)
        }

        removeBtn.setOnClickListener {
            dtcContainer.removeView(row)
            dtcs.remove(code)
            SimGeneratorGui.dtcs.remove(code)
        }

        dtcContainer.addView(row)
    }

    val dtcInput = view.findViewById<EditText>(R.id.dtc_entry)

    view.findViewById<Button>(R.id.dtc_entery_validate).apply {
        setOnClickListener {
            val v = dtcInput.text.toString().uppercase()
            if (v.isNotEmpty()) {
                addDtcRow(v)
                dtcInput.text.clear()
            }
        }
    }

    view.findViewById<CheckBox>(R.id.mil_state).apply {
        setOnCheckedChangeListener { _, v -> SimGeneratorGui.mil = v }
    }

    activity.dtcClearedCheck = view.findViewById<CheckBox>(R.id.dtcs_cleared).apply {
        setOnCheckedChangeListener { _, v -> SimGeneratorGui.dtcCleared = v }
    }

    view.findViewById<EditText>(R.id.ecu_name).apply {
        addTextChangedListener { SimGeneratorGui.ecuName = it.toString() }
    }

    view.findViewById<EditText>(R.id.vin).apply {
        addTextChangedListener { SimGeneratorGui.vin = it.toString() }
    }
    libautodiag.setResponseGuiByAddress(address.toByte())
    val ecu = EcuConfig(
        id = address,
        name = name,
        type = EcuType.GUI,
        screen = view
    )
    return ecu
}