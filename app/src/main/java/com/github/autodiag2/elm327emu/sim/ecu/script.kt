package com.github.autodiag2.elm327emu.sim.ecu

import android.content.ClipData
import android.content.ClipboardManager
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.github.autodiag2.elm327emu.MainActivity
import com.github.autodiag2.elm327emu.R
import com.github.autodiag2.elm327emu.libautodiag
import org.json.JSONObject
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.jse.JsePlatform
import com.github.autodiag2.elm327emu.LogLevel
import android.content.Context
import com.github.autodiag2.elm327emu.EmulatorForegroundService

interface EcuByteArrayHandler {
    fun response(request: ByteArray): ByteArray
}


class LuaJEcuHandler(
    script: String,
    private val errorView: TextView
) : EcuByteArrayHandler {

    private var globals = JsePlatform.standardGlobals()
    private var responseFunc: LuaValue = LuaValue.NIL

    init {
        loadScript(script)
    }

    fun getString(resId: Int, vararg formatArgs: Any?): String {
        return errorView.context.getString(resId, *formatArgs.map { it ?: "" }.toTypedArray())
    }

    fun reload(script: String) {
        loadScript(script)
    }

    private fun appendError(msg: String) {
        errorView.post {
            errorView.append(msg + "\n")
        }
    }

    private fun loadScript(script: String) {
        try {
            globals = JsePlatform.standardGlobals()

            val chunk = globals.load(script)
            chunk.call()

            val func = globals.get("response")

            if (!func.isfunction()) {
                appendError(getString(R.string.sim_ecu_script_lua_missing_function))
                responseFunc = LuaValue.NIL
                return
            }

            responseFunc = func

        } catch (e: Exception) {
            appendError(getString(R.string.sim_ecu_script_lua_load_error, e.message))
            responseFunc = LuaValue.NIL
        }
    }

    override fun response(request: ByteArray): ByteArray {

        if (!responseFunc.isfunction()) {
            appendError(getString(R.string.sim_ecu_script_lua_response_error))
            return byteArrayOf()
        }

        return try {
            val luaReq = LuaTable()

            for (i in request.indices) {
                luaReq.set(i + 1, LuaValue.valueOf(request[i].toInt() and 0xFF))
            }

            val result = responseFunc.call(luaReq)

            if (!result.istable()) {
                appendError(getString(R.string.sim_ecu_script_lua_response_error_missing_table))
                return byteArrayOf()
            }

            val len = result.length()

            ByteArray(len) { i ->
                val v = result.get(i + 1)

                if (!v.isnumber()) {
                    appendError(getString(R.string.sim_ecu_script_lua_response_error_non_number, i + 1))
                    return byteArrayOf()
                }

                val value = v.toint()

                if (value !in 0..255) {
                    appendError(getString(R.string.sim_ecu_script_lua_response_error_out_of_range, i + 1))
                    return byteArrayOf()
                }

                value.toByte()
            }

        } catch (e: Exception) {
            appendError(getString(R.string.sim_ecu_script_lua_runtime_error, e.message))
            byteArrayOf()
        }
    }
}

class EcuScriptView(context: Context) : EcuView(context) {
    public lateinit var onApply: ((text: String) -> Unit)
    public val luaEditor: EditText
    
    init {
        LayoutInflater.from(context).inflate(R.layout.sim_ecu_script, this, true)
        luaEditor = findViewById<EditText>(R.id.lua_editor)

        val applyScript = findViewById<Button>(R.id.apply_script)
        applyScript.setOnClickListener {
            onApply.invoke(luaEditor.text.toString())
        }
        val copyBtn = findViewById<Button>(R.id.copy_script)
        val pasteBtn = findViewById<Button>(R.id.paste_script)
        val clearBtn = findViewById<Button>(R.id.clear_script)

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        copyBtn.setOnClickListener {
            val text = luaEditor.text.toString()

            if (text.isNotEmpty()) {
                val clip = ClipData.newPlainText("lua_script", text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, getString(context,
                    R.string.sim_ecu_script_copy_success
                ), Toast.LENGTH_SHORT).show()
            }
        }
        pasteBtn.setOnClickListener {
            if (clipboard.hasPrimaryClip()) {
                val item = clipboard.primaryClip?.getItemAt(0)
                val pasted = item?.coerceToText(context)?.toString()

                if (!pasted.isNullOrEmpty()) {
                    luaEditor.setText(pasted)
                    luaEditor.setSelection(pasted.length) // move cursor to end
                }
            }
        }
        clearBtn.setOnClickListener {
            luaEditor.setText(
                "function response(req)\n" +
                        "    return {}\n" +
                        "end"
            )
        }
    }

}
class EcuScript(
    address: EcuAddress = DEFAULT_ADDRESS,
    name: String? = null,
    service: EmulatorForegroundService
): Ecu(EcuType.bytearray, service, address, name ?: service.getString(EcuType.bytearray.label_id)) {
    init {
        libautodiag.registerOnSignalReceived(address, signalHandler)
    }

    fun onApply(text: String) {
        updateScript(text)
    }

    override fun attachView(view: EcuView?) {
        super.attachView(view)
        assert(view is EcuScriptView?)
        val view = view as EcuScriptView?
        this.view = view
        if ( view != null ) {
            view.onApply = ::onApply
            updateScript(view.luaEditor.text.toString())
        }
    }

    override fun loadStateFromGeneratorWhenShowing() {
        // leave empty state stored externally
    }

    override fun fromJsonInternal(content: JSONObject) {
        val script = content.optString("script", "")
        updateScript(script)
        val view = this.view as EcuScriptView?
        if ( view != null ) {
            val activity = view.context as MainActivity
            activity.runOnUiThread {
                view.luaEditor.setText(script)
            }
        }
    }

    fun getScript(): String {
        val view = view as EcuScriptView?
        if ( view == null ) {
            return ""
        }
        val luaEditor = view.findViewById<EditText>(R.id.lua_editor)
        return luaEditor.text.toString()
    }

    override fun toJsonInternal(): JSONObject {
        val obj = JSONObject()
        obj.put("script", getScript())
        return obj
    }

    fun updateScript(script: String) {
        val view = view as EcuScriptView?
        if ( view == null ) {
            return
        }
        val errorReturn = view.findViewById<TextView>(R.id.error_return)
        val activity = view.context as MainActivity
        try {
            val handler = LuaJEcuHandler(script, errorReturn)

            // bind to ECU (native side)
            libautodiag.setResponseByteArrayByAddress(
                address,
                handler
            )
            activity.runOnUiThread {
                errorReturn.setText(getString(view.context,
                    R.string.sim_ecu_script_lua_parse_success
                ))
            }
        } catch (e: Exception) {
            activity.runOnUiThread {
                errorReturn.setText(getString(view.context,
                    R.string.sim_ecu_script_lua_load_parse_error, e.message))
            }
        }
    }


}