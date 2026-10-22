package com.github.autodiag2.elm327emu.sim.ecu

import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import com.github.autodiag2.elm327emu.MainActivity
import com.github.autodiag2.elm327emu.R
import com.github.autodiag2.elm327emu.libautodiag
import org.json.JSONArray
import org.json.JSONObject
import androidx.appcompat.widget.SwitchCompat
import android.content.Context
import com.github.autodiag2.elm327emu.EmulatorForegroundService

class EcuCycleView(context: Context): EcuView(context) {
    
    public val gears: EditText
    public val use_signals: SwitchCompat
    public lateinit var onValidate: (() -> Unit)
    
    init {
        LayoutInflater.from(context).inflate(R.layout.sim_ecu_cycle, this, true)
        gears = findViewById(R.id.sim_ecu_cycle_gears_input)
        use_signals = findViewById(R.id.sim_ecu_cycle_use_signals)
        findViewById<Button>(R.id.sim_ecu_cycle_gears_validate).setOnClickListener {
            onValidate.invoke()
        }
    }
    
    fun fromJsonInternal(content: JSONObject) {
        gears.setText(content.optInt("gears", 10).toString())
        use_signals.isChecked = content.optBoolean("use_signals", false)
    }

}

class EcuCycle(
    address: EcuAddress = DEFAULT_ADDRESS,
    name: String? = null,
    service: EmulatorForegroundService
): Ecu(EcuType.cycle, service, address, name ?: service.getString(EcuType.cycle.label_id)) {

    init {
        libautodiag.setResponseTypeByAddress(address, EcuType.cycle.name)
        libautodiag.registerOnSignalReceived(address, signalHandler)
    }

    override fun attachView(view: EcuView?) {
        super.attachView(view)
        assert(view is EcuCycleView?)
        val view = view as EcuCycleView?
        this.view = view
        if ( view != null ) {
            view.onValidate = ::onValidate
        }
    }

    fun onValidate() {
        libautodiag.simEcuLoadFromJson(address.toByte(), toJson().toString())
    }

    override fun toJsonInternal(): JSONObject {
        val content = JSONObject()
        val view = this.view as EcuCycleView?
        if ( view == null ) {
            return content
        }
        content.put("gears", view.gears.getText().toString().toInt())
        content.put("use_signals", view.use_signals.isChecked)
        return content
    }

    override fun fromJsonInternal(content: JSONObject) {
        val view = this.view as EcuCycleView?
        if ( view == null ) {
            return
        }
        val activity = view.context as MainActivity
        activity.runOnUiThread {
            view.fromJsonInternal(content)
        }
    }

}