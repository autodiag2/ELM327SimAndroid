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
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import com.github.autodiag2.elm327emu.MainActivity
import com.github.autodiag2.elm327emu.R
import com.github.autodiag2.elm327emu.SimGeneratorGuiManager
import com.github.autodiag2.elm327emu.SimSignal
import com.github.autodiag2.elm327emu.libautodiag
import org.json.JSONArray
import org.json.JSONObject
import com.github.autodiag2.elm327emu.libautodiag.SignalReceivedHandler
import com.github.autodiag2.elm327emu.sim.ecu.Ecu
import android.util.Log
import com.github.autodiag2.elm327emu.EmulatorForegroundService

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
fun getSignalInitialValue(address: EcuAddress, signal: SimSignal): Double {
    val v = libautodiag.getSignalValue(address, signal.path)
    if (!v.isNaN()) {
        return v
    }
    return signal.min
}
class EcuGuiView(context: Context, allSignals:  Array<SimSignal>): EcuView(context) {
    public val dynamicSignalsContainer: LinearLayout
    public val dtcContainer: LinearLayout
    public val dtcClearedCheck: CheckBox
    public val ecuName: EditText
    public val vin: EditText
    public val milState: CheckBox
    public lateinit var onSignalAdd: ((signal: SimSignal) -> Unit)
    public lateinit var onSignalValueChanged: ((signal: SimSignal, value: Double) -> Unit)
    public lateinit var onDtcAddByCode: ((code: String) -> Unit)
    public lateinit var onSignalRemoved: ((signal: SimSignal) -> Unit)
    public lateinit var get_Address: (() -> EcuAddress)
    public lateinit var onDTCRemove: ((code: String) -> Unit)
    public lateinit var onDTCsClearedStateChange: ((newState: Boolean) -> Unit)
    public lateinit var onMilStateChange: ((newState: Boolean) -> Unit)
    public lateinit var onVinChanged: ((newVin: String) -> Unit)
    public lateinit var onEcuNameChanged: ((newEcuName: String) -> Unit)

    init {
        LayoutInflater.from(context).inflate(R.layout.sim_ecu_gui, this, true)
        dynamicSignalsContainer = findViewById(R.id.signal_container)
        dtcContainer = findViewById(R.id.dtc_list)
        ecuName = findViewById(R.id.ecu_name)
        ecuName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            }

            override fun afterTextChanged(s: Editable?) {
                onEcuNameChanged.invoke(s.toString())
            }
        })
        vin = findViewById(R.id.vin)
        vin.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            }
            
            override fun afterTextChanged(s: Editable?) {
                onVinChanged.invoke(s.toString())
            }
        })
        milState = findViewById(R.id.mil_state)
        milState.setOnCheckedChangeListener { _, isChecked ->
            onMilStateChange.invoke(isChecked)
        }
        dtcClearedCheck = findViewById(R.id.dtcs_cleared)
        dtcClearedCheck.setOnCheckedChangeListener { _, isChecked ->
            onDTCsClearedStateChange.invoke(isChecked)
        }
        val signalSpinner = findViewById<Spinner>(R.id.signal_choice)
        val spinnerAdapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_item,
            allSignals.map { getName(context, it) }
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        signalSpinner.adapter = spinnerAdapter

        findViewById<Button>(R.id.signal_add).apply {
            setOnClickListener {
                val index = signalSpinner.selectedItemPosition
                if (!(index in allSignals.indices)) {
                    return@setOnClickListener
                }
                val signal = allSignals[index]
                onSignalAdd.invoke(signal)
            }
        }

        val dtcInput = findViewById<EditText>(R.id.dtc_entry)

        findViewById<Button>(R.id.dtc_entry_validate).apply {
            setOnClickListener {
                val v = dtcInput.getText().toString().uppercase()
                if (v.isNotEmpty()) {
                    onDtcAddByCode.invoke(v)
                    dtcInput.text.clear()
                }
            }
        }
    }
    fun addSignal(signal: SimSignal) {
        val title = TextView(context).apply {
            text = if (signal.unit.isNullOrBlank()) getName(context, signal) else "${getName(context, signal)} (${signal.unit})"
        }

        val step = if (signal.step <= 0.0) 1.0 else signal.step
        val scale = (1.0 / step).toInt().coerceAtLeast(1)
        val minI = (signal.min * scale).toInt()
        val maxI = (signal.max * scale).toInt()
        val initialI = (((getSignalInitialValue(get_Address.invoke(), signal)).coerceIn(signal.min, signal.max)) * scale).toInt()

        val valueText = TextView(context).apply {
            val shown = initialI.toDouble() / scale
            text = if (signal.unit.isNullOrBlank()) "$shown" else "$shown ${signal.unit}"
        }

        val seek = SeekBar(context).apply {
            max = (maxI - minI).coerceAtLeast(0)
            progress = (initialI - minI).coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    val v = (p + minI).toDouble() / scale
                    valueText.text = if (signal.unit.isNullOrBlank()) "$v" else "$v ${signal.unit}"
                    onSignalValueChanged.invoke(signal, v)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        
        val removeBtn = Button(context).apply {
            text = getString(context, R.string.sim_ecu_gui_remove_signal)
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(removeBtn)
        }

        val block = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12, 0, 12)
            addView(headerRow)
            addView(valueText)
            addView(seek)
        }

        removeBtn.setOnClickListener {
            onSignalRemoved.invoke(signal)
            dynamicSignalsContainer.removeView(block)
        }

        dynamicSignalsContainer.addView(block)
    }
    fun onClearSignals() {
        dynamicSignalsContainer.removeAllViews()
    }
    fun onClearDTCs() {
        dtcContainer.removeAllViews()
    }
    fun addDtcByCode(code: String) {
        val text = TextView(context).apply {
            text = code
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val removeBtn = Button(context)
        removeBtn.text = getString(context, R.string.sim_ecu_gui_remove_dtc)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
            addView(text)
            addView(removeBtn)
        }

        removeBtn.setOnClickListener {
            dtcContainer.removeView(row)
            onDTCRemove.invoke(code)
        }

        dtcContainer.addView(row)
    }
}

class EcuGui(
    address: EcuAddress = DEFAULT_ADDRESS,
    name: String? = null,
    service: EmulatorForegroundService,
) : Ecu(EcuType.gui, service, address, name ?: service.getString(EcuType.gui.label_id)) {
    val allSignals: List<SimSignal> = libautodiag.getSimSignals().sortedBy { it.name.lowercase() }
    val dtcs: MutableList<String> = mutableListOf()
    val signals: MutableMap<String, Double> = linkedMapOf()

    var dtcClearedChecked: Boolean = false
    var ecuName: String = ""
    var vin: String = ""
    var milState: Boolean = false

    init {
        addDefaultSignals()
        SimGeneratorGuiManager.add(address, this)
        libautodiag.setResponseGuiByAddress(address)
        libautodiag.registerOnSignalReceived(address, signalHandler)
    }

    override fun attachView(view: EcuView?) {
        super.attachView(view)
        assert(view is EcuGuiView?)
        val guiView = view as EcuGuiView?
        this.view = view
        if ( guiView != null ) {
            guiView.onSignalAdd = ::onSignalAdd
            guiView.onDtcAddByCode = ::addDtcByCode
            guiView.onSignalValueChanged = ::onSignalValueChanged
            guiView.onSignalRemoved = ::onSignalRemoved
            guiView.get_Address = ::get_Address
            guiView.onDTCRemove = ::onDTCRemove
            guiView.onDTCsClearedStateChange = { newState ->
                dtcClearedChecked = newState
            }
            guiView.onMilStateChange = { newState ->
                milState = newState
            }
            guiView.onEcuNameChanged = { newEcuName ->
                this.ecuName = newEcuName
            }
            guiView.onVinChanged = { newVin ->
                this.vin = newVin
            }
            guiView.post {
                for(signal_entry in signals) {
                    val signal = allSignals.find { it.path == signal_entry.key }
                    if ( signal == null ) {
                        Log.e("EcuGui", "signal not found")
                        continue
                    }
                    guiView.addSignal(signal)
                }
            }
        }
    }

    fun get_Address(): EcuAddress {
        return address
    }

    fun onSignalRemoved(signal: SimSignal) {
        signals.remove(signal.path)
    }
    fun onSignalValueChanged(signal: SimSignal, value: Double) {
        setSignalValue(signal.path, value)
    }

    fun onSignalAdd(signal: SimSignal) {
        addSignal(signal)
    }

    fun onDTCRemove(code: String) {
        dtcs.remove(code)
    }

    override fun loadStateFromGeneratorWhenShowing() {
        // leave empty state stored externally
    }

    fun getECUName(): String {
        return ecuName
    }

    fun getVIN(): String {
        return vin
    }

    fun getMILState(): Boolean {
        return milState
    }

    fun setMILChecked(state: Boolean) {
        milState = state
        val view = this.view as EcuGuiView?
        if ( view != null ) {
            val activity = view.context as MainActivity
            val mil = view.findViewById<CheckBox>(R.id.mil_state)
            activity.runOnUiThread {
                mil.isChecked = state
            }
        }
    }

    fun areDTCsCleared(): Boolean {
        return dtcClearedChecked
    }

    fun setDTCsCleared(state: Boolean) {
        dtcClearedChecked = state
        val view = this.view as EcuGuiView?
        if ( view != null ) {
            val activity = view.context as MainActivity
            activity.runOnUiThread {
                view.dtcClearedCheck.isChecked = state
            }
        }
    }

    fun setSignalValue(path: String, value: Double) {
        signals[path] = value
    }

    fun addSignal(signal: SimSignal) {
        if (signals.containsKey(signal.path)) {
            return
        }
        signals.put(signal.path, 0.0)

        val step = if (signal.step <= 0.0) 1.0 else signal.step
        val scale = (1.0 / step).toInt().coerceAtLeast(1)
        val initialI = (((getSignalInitialValue(address, signal)).coerceIn(signal.min, signal.max)) * scale).toInt()

        setSignalValue(signal.path, initialI.toDouble() / scale)
        val view = this.view as EcuGuiView?
        if ( view != null ) {
            view.post {
                view.addSignal(signal)
            }
        }
    }

    fun addSignalByPath(path: String) {
        allSignals.firstOrNull { it.path == path }?.let { addSignal(it) }
    }

    fun addDefaultSignals() {
        addSignalByPath("SAEJ1979.engine_speed")
        addSignalByPath("SAEJ1979.vehicle_speed")
        addSignalByPath("SAEJ1979.coolant_temp")
    }
    fun clearSignals() {
        val view = this.view as EcuGuiView?
        if ( view != null ) {
            view.onClearSignals()
        }
        signals.clear()
    }

    fun clearDTCs() {
        dtcs.clear()
        val view = this.view as EcuGuiView?
        if ( view != null ) {
            view.onClearDTCs()
        }
    }

    fun addDtcByCode(code: String) {
        if (code.isBlank()) return

        dtcs.add(code)

        val view = this.view as EcuGuiView?
        if ( view != null ) {
            view.addDtcByCode(code)
        }
    }

    fun set_EcuName(name: String) {
        ecuName = name
        val view = this.view as EcuGuiView?
        if ( view != null ) {
            val activity = view.context as MainActivity
            activity.runOnUiThread {
                view.ecuName.setText(name)
            }
        }
    }

    fun setVIN(name: String) {
        vin = name
        val view = this.view as EcuGuiView?
        if ( view != null ) {
            val activity = view.context as MainActivity
            activity.runOnUiThread {
                view.vin.setText(name)
            }
        }
    }

    override fun fromJsonInternal(content: JSONObject) {
        set_EcuName(content.optString("ecuName", ""))
        setVIN(content.optString("vin", ""))
        setMILChecked(content.optBoolean("mil", false))
        setDTCsCleared(content.optBoolean("dtcCleared", false))

        // dtcs
        clearDTCs()
        val dtcs = content.optJSONArray("dtcs")
        if (dtcs != null) {
            for (j in 0 until dtcs.length()) {
                addDtcByCode(dtcs.getString(j))
            }
        }

        // signals
        clearSignals()
        val signals = content.optJSONArray("signals")
        if (signals != null) {
            for (j in 0 until signals.length()) {
                val s = signals.getJSONObject(j)
                val path = s.getString("path")
                val value = s.getDouble("value")

                addSignalByPath(path)
                setSignalValue(path, value)
            }
        }
    }

    override fun toJsonInternal(): JSONObject {
        val state = JSONObject()

        state.put("ecuName", getECUName())
        state.put("vin", getVIN())
        state.put("mil", getMILState())
        state.put("dtcCleared", areDTCsCleared())

        val dtcs = JSONArray()
        this.dtcs.forEach { dtcs.put(it) }
        state.put("dtcs", dtcs)

        // signals
        val signals = JSONArray()
        this.signals.forEach { entry ->
            val value = libautodiag.getSignalValue(address, entry.key)
            if (!value.isNaN()) {
                val s = JSONObject()
                s.put("path", entry.key)
                s.put("value", value)
                signals.put(s)
            }
        }

        state.put("signals", signals)
        return state
    }
}