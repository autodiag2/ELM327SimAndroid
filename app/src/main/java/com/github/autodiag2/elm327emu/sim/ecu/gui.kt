package com.github.autodiag2.elm327emu.sim.ecu

import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.content.Context
import android.view.LayoutInflater
import androidx.constraintlayout.widget.ConstraintLayout
import com.github.autodiag2.elm327emu.EcuConfig
import com.github.autodiag2.elm327emu.EcuType
import com.github.autodiag2.elm327emu.MainActivity
import com.github.autodiag2.elm327emu.R
import com.github.autodiag2.elm327emu.SimGeneratorGuiManager
import com.github.autodiag2.elm327emu.SimSignal
import com.github.autodiag2.elm327emu.libautodiag

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
    val address: Byte,
    private val activity: MainActivity
) : ConstraintLayout(activity) {

    private val dynamicSignalsContainer: LinearLayout
    private val dtcContainer: LinearLayout
    private val dtcClearedCheck: CheckBox
    private val ecuName: EditText
    private val vin: EditText
    private val milState: CheckBox
    val allSignals = libautodiag.getSimSignals().sortedBy { it.name.lowercase() }
    val dtcs: MutableList<String> = mutableListOf()
    val signals: MutableMap<String, Double> = linkedMapOf()

    init {
        LayoutInflater.from(activity).inflate(R.layout.sim_ecu_gui, this, true)
        dynamicSignalsContainer = findViewById(R.id.signal_container)
        dtcContainer = findViewById(R.id.dtc_list)
        ecuName = findViewById(R.id.ecu_name)
        vin = findViewById(R.id.vin)
        milState = findViewById(R.id.mil_state)
        dtcClearedCheck = findViewById(R.id.dtcs_cleared)
        addDefaultSignals()
        SimGeneratorGuiManager.add(address, this)
    }

    fun getECUName(): String {
        return ecuName.text.toString()
    }

    fun getVIN(): String {
        return vin.text.toString()
    }

    fun getMILState(): Boolean {
        return milState.isChecked
    }

    fun areDTCsCleared(): Boolean {
        return dtcClearedCheck.isChecked
    }

    fun setDTCsCleared(state: Boolean) {
        activity.runOnUiThread {
            dtcClearedCheck.isChecked = state
        }
    }

    fun getSignalInitialValue(signal: SimSignal): Double {
        val v = libautodiag.getSignalValue(address, signal.path)
        if (!v.isNaN()) {
            return v
        }
        return signal.min
    }

    fun setSignalValue(path: String, value: Double) {
        signals[path] = value
    }

    fun addSignalWidget(signal: SimSignal) {
        if (signals.containsKey(signal.path)) {
            return
        }
        signals.put(signal.path, 0.0)

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
            text = getString(activity, R.string.sim_ecu_gui_remove_signal)
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
            signals.remove(signal.path)
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
        signals.clear()
    }

    fun clearDTCs() {
        dtcs.clear()
        dtcContainer.removeAllViews()
    }

    fun addDtcByCode(code: String) {
        if (code.isBlank()) return

        dtcs.add(code)

        val text = TextView(activity).apply {
            text = code
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val removeBtn = Button(activity)
        removeBtn.text = getString(activity, R.string.sim_ecu_gui_remove_dtc)

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
            addView(text)
            addView(removeBtn)
        }

        removeBtn.setOnClickListener {
            dtcContainer.removeView(row)
            dtcs.remove(code)
        }

        dtcContainer.addView(row)
    }
}

fun buildEcuGuiConfig(address: Int, name: String, activity: MainActivity): EcuConfig {
    val view = EcuGuiView(address.toByte(), activity)

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

    libautodiag.setResponseGuiByAddress(address.toByte())
    val ecu = EcuConfig(
        id = address,
        name = name,
        type = EcuType.GUI,
        screen = view
    )
    return ecu
}