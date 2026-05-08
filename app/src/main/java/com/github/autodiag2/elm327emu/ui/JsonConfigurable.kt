package com.github.autodiag2.elm327emu.ui

import org.json.JSONObject

interface JsonConfigurable {

    fun toJson(): JSONObject

    fun fromJson(desc: JSONObject)

}