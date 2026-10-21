package com.github.autodiag2.elm327emu.sim.ecu

import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.github.autodiag2.elm327emu.MainActivity
import com.github.autodiag2.elm327emu.R
import com.github.autodiag2.elm327emu.libautodiag
import org.json.JSONArray
import org.json.JSONObject
import com.github.autodiag2.elm327emu.EmulatorForegroundService

import android.content.Context

class EcuCitroenC5X7View(context: Context): EcuView(context) {
    public val dtcsView: LinearLayout
    public lateinit var onValidate: (() -> Unit)
    init {
        LayoutInflater.from(context).inflate(R.layout.sim_ecu_citroen_c5_x7, this, true)

        dtcsView = findViewById(R.id.sim_ecu_citroen_c5_x7_dtc_list)

        val dtcEntry: EditText = findViewById(R.id.sim_ecu_citroen_c5_x7_dtc_entry)
        val addDTC: Button = findViewById(R.id.sim_ecu_citroen_c5_x7_dtc_entry_validate)

        addDTC.setOnClickListener {
            val dtc = dtcEntry.text.toString().trim()
            if (dtc.isNotEmpty()) {
                val label = TextView(context)
                label.text = dtc
                dtcsView.addView(label)
                dtcEntry.text.clear()
            }
        }

        val validate: Button = findViewById(R.id.sim_ecu_citroen_c5_x7_validate)
        validate.setOnClickListener {
            onValidate.invoke()
        }
    }
    public fun fromJsonInternal(content: JSONObject) {
        findViewById<EditText>(R.id.sim_ecu_citroen_c5_x7_vin_input)
            .setText(content.optString("vin", ""))

        val seed = content.optLong("seed", 0L)
        findViewById<EditText>(R.id.sim_ecu_citroen_c5_x7_seed_input)
            .setText(seed.toString())

        dtcsView.removeAllViews()

        val dtcs = content.optJSONArray("dtcs") ?: JSONArray()
        for (i in 0 until dtcs.length()) {
            val label = TextView(context)
            label.text = dtcs.optString(i)
            dtcsView.addView(label)
        }
    }
}

class EcuCitroenC5X7(
    address: EcuAddress = DEFAULT_ADDRESS,
    name: String? = null,
    service: EmulatorForegroundService
) : Ecu(
        EcuType.citroen_c5_x7,
        service,
        address, 
        name ?: service.getString(EcuType.citroen_c5_x7.label_id)
    ) {

    init {
        libautodiag.setResponseTypeByAddress(address, EcuType.citroen_c5_x7.name)
        libautodiag.registerOnSignalReceived(address, signalHandler)
    }

    fun onValidate() {
        libautodiag.simEcuLoadFromJson(address.toByte(), toJson().toString())
    }

    override fun attachView(view: EcuView?) {
        assert(view is EcuCitroenC5X7View)
        val view = view as EcuCitroenC5X7View
        this.view = view
        view.onValidate = ::onValidate
    }

    override fun toJsonInternal(): JSONObject {
        val view = this.view as EcuCitroenC5X7View?
        val content = JSONObject()
        if ( view == null ) {
            return content
        }

        content.put(
            "vin",
            view.findViewById<EditText>(R.id.sim_ecu_citroen_c5_x7_vin_input).text.toString()
        )

        content.put(
            "seed",
            view.findViewById<EditText>(R.id.sim_ecu_citroen_c5_x7_seed_input).text.toString().toLongOrNull() ?: 0L
        )

        val dtcs = JSONArray()

        for (i in 0 until view.dtcsView.childCount) {
            val child = view.dtcsView.getChildAt(i)
            if (child is TextView) {
                dtcs.put(child.text.toString())
            }
        }

        content.put("dtcs", dtcs)

        return content
    }

    override fun fromJsonInternal(content: JSONObject) {
        val view = this.view as EcuCitroenC5X7View?
        if ( view == null ) {
            return
        }
        val activity = view.context as MainActivity
        activity.runOnUiThread {
            view.fromJsonInternal(content)
        }
    }

}