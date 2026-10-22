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

class EcuRandomView(context: Context) : EcuView(context) {

    public val seed: EditText
    public val use_signals: SwitchCompat
    public lateinit var onValidate: (() -> Unit)
    init {
        LayoutInflater.from(context).inflate(R.layout.sim_ecu_random, this, true)
        seed = findViewById(R.id.sim_ecu_random_seed_input)
        use_signals = findViewById(R.id.sim_ecu_random_use_signals)
        findViewById<Button>(R.id.sim_ecu_random_validate).setOnClickListener {
            onValidate.invoke()
        }
    }

    fun fromJsonInternal(content: JSONObject) {
        seed.setText(content.getInt("seed").toString())
        use_signals.isChecked = content.getBoolean("use_signals")
    }
}

class EcuRandom(
    address: EcuAddress = DEFAULT_ADDRESS,
    name: String? = null,
    service: EmulatorForegroundService
): Ecu(EcuType.random, service, address, name ?: service.getString(EcuType.random.label_id)) {

    init {
        libautodiag.setResponseTypeByAddress(address, EcuType.random.name)
        libautodiag.registerOnSignalReceived(address, signalHandler)
    }

    fun onValidate() {
        libautodiag.simEcuLoadFromJson(address.toByte(), toJson().toString())
    }
    
    override fun attachView(view: EcuView?) {
        super.attachView(view)
        assert(view is EcuRandomView?)
        val view = view as EcuRandomView?
        this.view = view
        if ( view != null ) {
            view.onValidate = ::onValidate
        }
    }

    override fun toJsonInternal(): JSONObject {
        val content = JSONObject()
        val view = this.view as EcuRandomView?
        if ( view == null ) {
            return content
        }
        content.put("seed", view.seed.getText().toString().toInt())
        content.put("use_signals", view.use_signals.isChecked)
        return content
    }

    override fun fromJsonInternal(content: JSONObject) {
        val view = this.view as EcuRandomView?
        if ( view == null ) {
            return
        }
        val activity = view.context as MainActivity
        activity.runOnUiThread {
            view.fromJsonInternal(content)
        }
    }

}
