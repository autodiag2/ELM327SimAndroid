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
import android.content.Context
import android.view.LayoutInflater
import androidx.constraintlayout.widget.ConstraintLayout

fun getName(context: Context, signal: SimSignal): String {
    val key = "signal_" + signal.path.replace(".", "_")

    val resId = context.resources.getIdentifier(
        key,
        "string",
        context.packageName
    )

    return if (resId != 0) context.getString(resId) else signal.path
}

fun getString(context: Context,resId: Int, vararg formatArgs: Any?): String {
    return context.getString(resId, *formatArgs.map { it ?: "" }.toTypedArray())
}

class EcuGuiView(
    private val activity: MainActivity,
    val ecuState: SimGeneratorGuiManager.EcuState
) : ConstraintLayout(activity) {

    val allSignals = libautodiag.getSimSignals().sortedBy { it.name.lowercase() }
    var dynamicSignalsContainer: LinearLayout
    var dtcContainer: LinearLayout
    lateinit var dtcClearedCheck: CheckBox

    init {
        LayoutInflater.from(activity).inflate(R.layout.sim_main_ecu_config_gui, this, true)
        dynamicSignalsContainer = findViewById(R.id.signal_container)
        dtcContainer = findViewById(R.id.dtc_list)
        addDefaultSignals()
    }

    fun getSignalInitialValue(signal: SimSignal): Double {
        val v = libautodiag.getSignalValue(ecuState.address, signal.path)
        if (!v.isNaN()) {
            return v
        }
        return signal.min
    }

    fun setSignalValue(path: String, value: Double) {
        libautodiag.setSignalValue(ecuState.address, path, value)
    }

    fun addSignalWidget(signal: SimSignal) {
        if (ecuState.signals.containsKey(signal.path)) {
            return
        }
        ecuState.signals.put(signal.path, 0.0)

        val title = TextView(activity).apply {
            text = if (signal.unit.isNullOrBlank()) getName(activity, signal) else "${getName(activity, signal)} (${signal.unit})"
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
                    setSignalValue(signal.path, v)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }

        setSignalValue(signal.path, initialI.toDouble() / scale)

        val removeBtn = Button(activity).apply {
            text = getString(activity, R.string.sim_main_ecu_config_gui_remove_signal)
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
            ecuState.signals.remove(signal.path)
            dynamicSignalsContainer.removeView(block)
        }

        dynamicSignalsContainer.addView(block)
    }

    fun addSignalByPath(path: String) {
        allSignals.firstOrNull { it.path == path }?.let { addSignalWidget(it) }
    }

    fun addDefaultSignals() {
        addSignalByPath("SAEJ1979.engine_speed")
        addSignalByPath("SAEJ1979.vehicle_speed")
        addSignalByPath("SAEJ1979.coolant_temp")
    }
    fun clearSignals() {
        dynamicSignalsContainer.removeAllViews()
        ecuState.signals.clear()
    }

    fun clearDTCs() {
        ecuState.dtcs.clear()
        dtcContainer.removeAllViews()
    }

    fun addDtcByCode(code: String) {
        if (code.isBlank()) return

        ecuState.dtcs.add(code)

        val text = TextView(activity).apply {
            text = code
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val removeBtn = Button(activity)
        removeBtn.text = getString(activity, R.string.sim_main_ecu_config_gui_remove_dtc)

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
            addView(text)
            addView(removeBtn)
        }

        removeBtn.setOnClickListener {
            dtcContainer.removeView(row)
            ecuState.dtcs.remove(code)
        }

        dtcContainer.addView(row)
    }
}

fun buildEcuGuiConfig(address: Int, name: String, activity: MainActivity): EcuConfig {
    val view = EcuGuiView(activity, SimGeneratorGuiManager.getOrCreate(address.toByte()))

    val signalSpinner = view.findViewById<Spinner>(R.id.signal_choice)
    val spinnerSignals = view.allSignals
    val spinnerAdapter = ArrayAdapter(
        activity,
        android.R.layout.simple_spinner_item,
        spinnerSignals.map { getName(activity, it) }
    ).apply {
        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }
    signalSpinner.adapter = spinnerAdapter

    view.findViewById<Button>(R.id.signal_add).apply {
        setOnClickListener {
            val index = signalSpinner.selectedItemPosition

            if (index in spinnerSignals.indices) {
                val signal = spinnerSignals[index]
                view.addSignalWidget(signal)
            }
        }
    }

    val dtcInput = view.findViewById<EditText>(R.id.dtc_entry)

    view.findViewById<Button>(R.id.dtc_entery_validate).apply {
        setOnClickListener {
            val v = dtcInput.text.toString().uppercase()
            if (v.isNotEmpty()) {
                view.addDtcByCode(v)
                dtcInput.text.clear()
            }
        }
    }

    view.findViewById<CheckBox>(R.id.mil_state).apply {
        setOnCheckedChangeListener { _, v -> SimGeneratorGuiManager.getOrCreate(address.toByte()).mil = v }
    }

    view.dtcClearedCheck = view.findViewById<CheckBox>(R.id.dtcs_cleared).apply {
        setOnCheckedChangeListener { _, v -> SimGeneratorGuiManager.getOrCreate(address.toByte()).dtcCleared = v }
    }

    view.findViewById<EditText>(R.id.ecu_name).apply {
        addTextChangedListener { SimGeneratorGuiManager.getOrCreate(address.toByte()).ecuName = it.toString() }
    }

    view.findViewById<EditText>(R.id.vin).apply {
        addTextChangedListener { SimGeneratorGuiManager.getOrCreate(address.toByte()).vin = it.toString() }
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