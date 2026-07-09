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

class EcuRandom(
    address: EcuAddress = DEFAULT_ADDRESS,
    name: String? = null,
    activity: MainActivity
): Ecu(EcuType.random, address, name ?: activity.getString(EcuType.random.label_id), activity) {

    private val seed: EditText
    private val use_signals: SwitchCompat

    init {
        LayoutInflater.from(activity).inflate(R.layout.sim_ecu_random, this, true)
        seed = findViewById(R.id.sim_ecu_random_seed_input)
        use_signals = findViewById(R.id.sim_ecu_random_use_signals)
        findViewById<Button>(R.id.sim_ecu_random_validate).setOnClickListener {
            libautodiag.simEcuLoadFromJson(address.toByte(), toJson().toString())
        }
        libautodiag.setResponseTypeByAddress(address, EcuType.random.name)
    }

    override fun toJsonInternal(): JSONObject {
        val content = JSONObject()
        content.put("seed", seed.getText().toString().toInt())
        content.put("use_signals", use_signals.isChecked)
        return content
    }

    override fun fromJsonInternal(content: JSONObject) {
        seed.setText(content.getInt("seed").toString())
        use_signals.isChecked = content.getBoolean("use_signals")
    }

}
