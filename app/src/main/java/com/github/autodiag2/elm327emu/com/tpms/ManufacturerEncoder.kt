package com.github.autodiag2.elm327emu.com.tpms

import java.util.UUID

data class TpmsReading(
    val pressurePsi: Int = 55,
    val temperatureC: Int = 55
)

data class TpmsManufacturerFrame(
    val companyId: Int,
    val payload: ByteArray
)

interface TpmsManufacturerEncoder {
    val id: String
    val displayName: String
    fun encode(reading: TpmsReading, sensorId: ByteArray): TpmsManufacturerFrame
}

object TpmsManufacturerEncoders {
    const val PREF_ENABLED = "com_ble_tpms_enabled"
    const val PREF_TYPE = "com_ble_tpms_type"

    val all: List<TpmsManufacturerEncoder> = listOf(JkTyreTreelEncoder())

    fun find(id: String?): TpmsManufacturerEncoder? = all.firstOrNull { it.id == id }
}

class JkTyreTreelEncoder : TpmsManufacturerEncoder {
    override val id = "jk_tyre_treel"
    override val displayName = "JK Tyre Treel"

    override fun encode(reading: TpmsReading, sensorId: ByteArray): TpmsManufacturerFrame {
        require(sensorId.size == 3) { "Treel sensorId must contain exactly 3 bytes" }

        val payload = byteArrayOf(
            0x02,
            0x15,
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xe0.toByte(),
            sensorId[0],
            sensorId[1],
            sensorId[2],
            reading.pressurePsi.coerceIn(0, 255).toByte(),
            (reading.temperatureC + TEMPERATURE_OFFSET).coerceIn(0, 255).toByte()
        )

        return TpmsManufacturerFrame(APPLE_COMPANY_ID, payload)
    }

    companion object {
        const val APPLE_COMPANY_ID = 0x004C
        const val TEMPERATURE_OFFSET = 110
        val ADVERTISED_UUID: UUID = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffe0")
    }
}

object TpmsGattProfile {
    val SERVICE_UUID: UUID = UUID.fromString("9f2c0001-75d8-4e7d-a28c-9b3b6c17e001")
    val TEMPERATURE_UUID: UUID = UUID.fromString("9f2c0002-75d8-4e7d-a28c-9b3b6c17e001")
    val PRESSURE_UUID: UUID = UUID.fromString("9f2c0003-75d8-4e7d-a28c-9b3b6c17e001")
    const val TEST_VALUE = 55
}
