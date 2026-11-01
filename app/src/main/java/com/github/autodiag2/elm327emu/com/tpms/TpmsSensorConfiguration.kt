package com.github.autodiag2.elm327emu.com.tpms

import android.content.SharedPreferences
import java.util.Locale

object TpmsSensorConfiguration {
    val positions = listOf("FL", "FR", "RL", "RR", "SP")

    private val defaultIds = mapOf(
        "FL" to "000001",
        "FR" to "000002",
        "RL" to "000003",
        "RR" to "000004",
        "SP" to "000005"
    )

    fun preferenceKey(position: String): String = "com_ble_tpms_sensor_id_${position.lowercase(Locale.ROOT)}"

    fun defaultId(position: String): String = defaultIds.getValue(position)

    fun defaultSensorId(position: String): ByteArray = parseId(defaultId(position))!!

    fun normalizeId(value: String): String? {
        val normalized = value.trim().uppercase(Locale.ROOT)
        return normalized.takeIf { it.matches(Regex("[0-9A-F]{6}")) }
    }

    fun parseId(value: String): ByteArray? = normalizeId(value)
        ?.chunked(2)
        ?.map { it.toInt(16).toByte() }
        ?.toByteArray()

    fun sensorId(
        preferences: SharedPreferences,
        position: String,
        defaultSensorId: ByteArray
    ): ByteArray = preferences.getString(preferenceKey(position), null)
        ?.let(::parseId)
        ?: defaultSensorId

    fun formatId(value: ByteArray): String = value.joinToString("") {
        String.format(Locale.ROOT, "%02X", it.toInt() and 0xFF)
    }
}
