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

class EcuCitroenC5X7(
    address: EcuAddress = DEFAULT_ADDRESS,
    name: String = EcuType.citroen_c5_x7.label,
    activity: MainActivity
) : Ecu(EcuType.citroen_c5_x7, address, name, activity) {

    private val dtcsView: LinearLayout

    init {
        LayoutInflater.from(activity).inflate(R.layout.sim_ecu_citroen_c5_x7, this, true)

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
            libautodiag.simEcuLoadFromJson(address.toByte(), toJson().toString())
        }

        libautodiag.setResponseTypeByAddress(address, EcuType.citroen_c5_x7.name)
    }

    override fun toJsonInternal(): JSONObject {
        val content = JSONObject()

        content.put(
            "vin",
            findViewById<EditText>(R.id.sim_ecu_citroen_c5_x7_vin_input).text.toString()
        )

        content.put(
            "seed",
            findViewById<EditText>(R.id.sim_ecu_citroen_c5_x7_seed_input).text.toString().toLongOrNull() ?: 0L
        )

        val dtcs = JSONArray()

        for (i in 0 until dtcsView.childCount) {
            val child = dtcsView.getChildAt(i)
            if (child is TextView) {
                dtcs.put(child.text.toString())
            }
        }

        content.put("dtcs", dtcs)

        return content
    }

    override fun fromJsonInternal(content: JSONObject) {
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