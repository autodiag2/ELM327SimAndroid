package com.github.autodiag2.elm327emu.sim.ecu

import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import com.github.autodiag2.elm327emu.MainActivity
import com.github.autodiag2.elm327emu.R
import com.github.autodiag2.elm327emu.libautodiag
import org.json.JSONArray
import org.json.JSONObject

class EcuRandom(
    address: EcuAddress = DEFAULT_ADDRESS,
    name: String = EcuType.RANDOM.toString(),
    activity: MainActivity
): Ecu(EcuType.RANDOM, address, name, activity) {

    private val seed: EditText

    init {
        LayoutInflater.from(activity).inflate(R.layout.sim_ecu_random, this, true)
        seed = findViewById(R.id.sim_ecu_random_seed_input)
        findViewById<Button>(R.id.sim_ecu_random_validate).setOnClickListener {
            libautodiag.simEcuLoadFromJson(address.toByte(), toJson().toString())
        }
        libautodiag.setResponseTypeByAddress(address, "random")
    }

    override fun toJsonInternal(): JSONObject {
        val content = JSONObject()
        content.put("seed", seed.getText().toString().toInt())
        return content
    }

    override fun fromJsonInternal(content: JSONObject) {
        seed.setText(content.getInt("seed"))
    }

}
