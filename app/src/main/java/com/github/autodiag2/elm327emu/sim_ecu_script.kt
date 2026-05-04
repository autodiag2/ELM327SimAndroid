package com.github.autodiag2.elm327emu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.jse.JsePlatform


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
                appendError(getString(R.string.sim_main_ecu_config_script_lua_missing_function))
                responseFunc = LuaValue.NIL
                return
            }

            responseFunc = func

        } catch (e: Exception) {
            appendError(getString(R.string.sim_main_ecu_config_script_lua_load_error, e.message))
            responseFunc = LuaValue.NIL
        }
    }

    override fun response(request: ByteArray): ByteArray {

        if (!responseFunc.isfunction()) {
            appendError(getString(R.string.sim_main_ecu_config_script_lua_response_error))
            return byteArrayOf()
        }

        return try {
            val luaReq = LuaTable()

            for (i in request.indices) {
                luaReq.set(i + 1, LuaValue.valueOf(request[i].toInt() and 0xFF))
            }

            val result = responseFunc.call(luaReq)

            if (!result.istable()) {
                appendError(getString(R.string.sim_main_ecu_config_script_lua_response_error_missing_table))
                return byteArrayOf()
            }

            val len = result.length()

            ByteArray(len) { i ->
                val v = result.get(i + 1)

                if (!v.isnumber()) {
                    appendError(getString(R.string.sim_main_ecu_config_script_lua_response_error_non_number, i + 1))
                    return byteArrayOf()
                }

                val value = v.toint()

                if (value !in 0..255) {
                    appendError(getString(R.string.sim_main_ecu_config_script_lua_response_error_out_of_range, i + 1))
                    return byteArrayOf()
                }

                value.toByte()
            }

        } catch (e: Exception) {
            appendError(getString(R.string.sim_main_ecu_config_script_lua_runtime_error, e.message))
            byteArrayOf()
        }
    }
}
fun getScript(ecu: EcuConfig): String {
    val luaEditor = ecu.screen.findViewById<EditText>(R.id.lua_editor)
    return luaEditor.text.toString()
}
fun updateScript(script: String, ecu: EcuConfig) {
    val errorReturn = ecu.screen.findViewById<TextView>(R.id.error_return)
    try {
        val handler = LuaJEcuHandler(script, errorReturn)

        // bind to ECU (native side)
        libautodiag.setResponseByteArrayByAddress(
            ecu.id.toByte(),
            handler
        )
        errorReturn.setText(getString(ecu.screen.context, R.string.sim_main_ecu_config_script_lua_parse_success))
    } catch (e: Exception) {
        errorReturn.setText(getString(ecu.screen.context, R.string.sim_main_ecu_config_script_lua_load_parse_error, e.message))
    }
}
fun buildSimScriptView(address: Int, name: String, activity: MainActivity): EcuConfig {
    val view = activity.layoutInflater.inflate(R.layout.sim_main_ecu_config_script, activity.contentFrame, false)
    val luaEditor = view.findViewById<EditText>(R.id.lua_editor)

    val ecu = EcuConfig(
        id = address,
        name = name,
        type = EcuType.SCRIPT,
        screen = view
    )
    val applyScript = view.findViewById<Button>(R.id.apply_script)
    applyScript.setOnClickListener {
        updateScript(luaEditor.text.toString(), ecu)
    }
    updateScript(luaEditor.text.toString(), ecu)
    val copyBtn = view.findViewById<Button>(R.id.copy_script)
    val pasteBtn = view.findViewById<Button>(R.id.paste_script)
    val clearBtn = view.findViewById<Button>(R.id.clear_script)

    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    copyBtn.setOnClickListener {
        val text = luaEditor.text.toString()

        if (text.isNotEmpty()) {
            val clip = ClipData.newPlainText("lua_script", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(activity, getString(activity, R.string.sim_main_ecu_config_script_copy_success), Toast.LENGTH_SHORT).show()
        }
    }
    pasteBtn.setOnClickListener {
        if (clipboard.hasPrimaryClip()) {
            val item = clipboard.primaryClip?.getItemAt(0)
            val pasted = item?.coerceToText(activity)?.toString()

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
    return ecu
}