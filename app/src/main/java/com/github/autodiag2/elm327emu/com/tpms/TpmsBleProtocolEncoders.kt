package com.github.autodiag2.elm327emu.com.tpms

import java.util.UUID
import kotlin.math.roundToInt

object TpmsBleProtocolIds {
    val TYPE_B_SENSOR_ID = UUID.fromString("0000a82b-0000-1000-8000-00805f9b34fb")
    val TYPE_C_SENSOR_ID = UUID.fromString("0000a82c-0000-1000-8000-00805f9b34fb")
    val TYPE_E_SENSOR_ID = UUID.fromString("0000a82e-0000-1000-8000-00805f9b34fb")
    val TYPE_F_SENSOR_ID = UUID.fromString("0000a82f-0000-1000-8000-00805f9b34fb")
    val TYPE_G_SENSOR_ID = UUID.fromString("0000a829-0000-1000-8000-00805f9b34fb")
    val BEKUBEE_TPMS = UUID.fromString("0000a82a-0000-1000-8000-00805f9b34fb")
}

private object TpmsBleEncoding {
    private const val PSI_TO_KPA = 6.894757293

    fun psi(reading: TpmsReading): Double =
        (reading.pressureCentiPsi ?: reading.pressurePsi * 100) / 100.0

    fun celsius(reading: TpmsReading): Double =
        (reading.temperatureCentiC ?: reading.temperatureC * 100) / 100.0

    fun pressureKpa(reading: TpmsReading): Double = psi(reading) * PSI_TO_KPA

    fun pressureBar(reading: TpmsReading): Double = psi(reading) / 14.503774

    fun voltageCode(reading: TpmsReading): Int =
        (((reading.batteryVoltageTenths ?: 29) / 10.0 - 1.22) * 100.0)
            .roundToInt()
            .coerceIn(0, 255)

    fun idData(uuid: UUID, sensorId: ByteArray): TpmsAdvertisingData =
        TpmsAdvertisingData.ServiceData(uuid, sensorId.copyOf())

    fun frame(vararg items: TpmsAdvertisingData): TpmsSensorFrame =
        TpmsSensorFrame(TpmsAdvertisingData.Composite(items.toList()))

    fun writeInt32Le(target: ByteArray, offset: Int, value: Long) {
        for (index in 0..3) {
            target[offset + index] = ((value ushr (index * 8)) and 0xff).toByte()
        }
    }

    fun writeInt16Be(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value ushr 8) and 0xff).toByte()
        target[offset + 1] = (value and 0xff).toByte()
    }

    fun companyFrame(
        companyId: Int,
        payload: ByteArray,
        vararg additional: TpmsAdvertisingData
    ): TpmsSensorFrame {
        val items = additional.toMutableList()
        items.add(TpmsAdvertisingData.ManufacturerData(companyId, payload))
        return frame(*items.toTypedArray())
    }

    fun batteryStatusCompanyId(reading: TpmsReading): Int =
        (((reading.batteryVoltageTenths ?: 29).coerceIn(0, 255)) shl 8) or
            (reading.statusFlags and 0x0f)
}

class TpmsBleTypeAEncoder : TpmsManufacturerEncoder {
    override val id = "tpms_ble_a"
    override val displayName = "TPMSII Type A"

    override fun encode(reading: TpmsReading, sensorId: ByteArray): TpmsSensorFrame {
        val payload = ByteArray(16)
        System.arraycopy(sensorId, 0, payload, 0, sensorId.size.coerceAtMost(6))
        val pressure = (TpmsBleEncoding.pressureBar(reading) * 100000.0)
            .roundToInt()
        val temperature = (TpmsBleEncoding.celsius(reading) * 100.0)
            .roundToInt()
        TpmsBleEncoding.writeInt32Le(payload, 6, pressure.toLong())
        TpmsBleEncoding.writeInt32Le(payload, 10, temperature.toLong())
        payload[14] = (reading.batteryPercent and 0xff).toByte()
        payload[15] = if (reading.statusFlags != 0) 1 else 0
        return TpmsBleEncoding.companyFrame(0x0100, payload)
    }
}

class TpmsBleTypeCEncoder : TpmsManufacturerEncoder {
    override val id = "tpms_ble_c"
    override val displayName = "Michelin TMS Type C"

    override fun encode(reading: TpmsReading, sensorId: ByteArray): TpmsSensorFrame {
        val payload = ByteArray(14)
        payload[0] = 0x01
        payload[1] = 0x03
        payload[2] = (TpmsBleEncoding.celsius(reading) + 60.0).roundToInt().toByte()
        payload[3] = 190.toByte()
        val rawPressure = ((TpmsBleEncoding.pressureBar(reading) + 1.0) * 1000.0)
            .roundToInt()
            .coerceIn(0, 65535)
        payload[4] = (rawPressure and 0xff).toByte()
        payload[5] = ((rawPressure ushr 8) and 0xff).toByte()
        return TpmsBleEncoding.companyFrame(
            0x0828,
            payload,
            TpmsBleEncoding.idData(TpmsBleProtocolIds.TYPE_C_SENSOR_ID, sensorId)
        )
    }
}

class TpmsBleTypeDEncoder : TpmsManufacturerEncoder {
    override val id = "tpms_ble_d"
    override val displayName = "WODHMIEY / ITPMS K Type D"

    override fun encode(reading: TpmsReading, sensorId: ByteArray): TpmsSensorFrame {
        val payload = ByteArray(15)
        val rawPressure = (TpmsBleEncoding.pressureBar(reading) / 0.03144)
            .roundToInt()
            .coerceIn(0, 511)
        payload[0] = TpmsBleEncoding.voltageCode(reading).toByte()
        payload[1] = (rawPressure and 0xff).toByte()
        payload[2] = (TpmsBleEncoding.celsius(reading) + 55.0).roundToInt().toByte()
        payload[3] = if (reading.statusFlags != 0) 1 else 0
        payload[6] = ((rawPressure ushr 8) and 0xff).toByte()
        if (sensorId.size >= 3) {
            payload[9] = sensorId[2]
            payload[10] = sensorId[1]
            payload[11] = sensorId[0]
        }
        return TpmsBleEncoding.companyFrame(0x00ac, payload)
    }
}

class TpmsBleTypeEEncoder : TpmsManufacturerEncoder {
    override val id = "tpms_ble_e"
    override val displayName = "Salutica FOBO Type E"

    override fun encode(reading: TpmsReading, sensorId: ByteArray): TpmsSensorFrame {
        val pressureKpa = TpmsBleEncoding.pressureKpa(reading)
            .roundToInt()
            .coerceIn(0, 1023)
        val batteryCode = (reading.batteryVoltageTenths ?: 29).coerceIn(0, 63)
        val rawValue = (batteryCode shl 10) or pressureKpa
        val manufacturerPayload = byteArrayOf(
            0,
            (TpmsBleEncoding.celsius(reading) + 50.0).roundToInt().toByte(),
            ((rawValue ushr 8) and 0xff).toByte(),
            (rawValue and 0xff).toByte()
        )
        val servicePayload = manufacturerPayload.copyOf().also {
            it[1] = (TpmsBleEncoding.celsius(reading) + 40.0).roundToInt().toByte()
        }
        return TpmsBleEncoding.companyFrame(
            0x0127,
            manufacturerPayload,
            TpmsAdvertisingData.ServiceData(
                UUID.fromString("0000012a-0000-1000-8000-00805f9b34fb"),
                servicePayload
            ),
            TpmsBleEncoding.idData(TpmsBleProtocolIds.TYPE_E_SENSOR_ID, sensorId)
        )
    }
}

class TpmsBleTypeFEncoder : TpmsManufacturerEncoder {
    override val id = "tpms_ble_f"
    override val displayName = "Bekubee HRTPMS Type F"

    override fun encode(reading: TpmsReading, sensorId: ByteArray): TpmsSensorFrame {
        val payload = ByteArray(9)
        payload[0] = TpmsBleEncoding.voltageCode(reading).toByte()
        payload[1] = (TpmsBleEncoding.celsius(reading) + 50.0).roundToInt().toByte()
        val rawPressure = (TpmsBleEncoding.pressureKpa(reading) + 100.0)
            .roundToInt()
            .coerceIn(0, 65535)
        TpmsBleEncoding.writeInt16Be(payload, 2, rawPressure)
        return TpmsBleEncoding.companyFrame(
            0x0002,
            payload,
            TpmsAdvertisingData.ServiceUuid(
                UUID.fromString("0000a827-0000-1000-8000-00805f9b34fb")
            ),
            TpmsBleEncoding.idData(TpmsBleProtocolIds.TYPE_F_SENSOR_ID, sensorId)
        )
    }
}

class TpmsBleTypeGEncoder : TpmsManufacturerEncoder {
    override val id = "tpms_ble_g"
    override val displayName = "Ai TPMS AI-8000 Type G"

    override fun encode(reading: TpmsReading, sensorId: ByteArray): TpmsSensorFrame {
        val absolutePressure = ((TpmsBleEncoding.psi(reading) + 14.696) * 10.0)
            .roundToInt()
            .coerceIn(0, 65535)
        val payload = byteArrayOf(
            TpmsBleEncoding.celsius(reading).roundToInt().toByte(),
            ((absolutePressure ushr 8) and 0xff).toByte(),
            (absolutePressure and 0xff).toByte()
        )
        return TpmsBleEncoding.companyFrame(
            TpmsBleEncoding.batteryStatusCompanyId(reading),
            payload,
            TpmsBleEncoding.idData(TpmsBleProtocolIds.TYPE_G_SENSOR_ID, sensorId)
        )
    }
}

class TpmsBleTypeHEncoder : TpmsManufacturerEncoder {
    override val id = "tpms_ble_h"
    override val displayName = "TC.TPMS Type H"

    override fun encode(reading: TpmsReading, sensorId: ByteArray): TpmsSensorFrame {
        val payload = ByteArray(9)
        val absolutePressure = ((TpmsBleEncoding.psi(reading) + 14.6) * 10.0)
            .roundToInt()
            .coerceIn(0, 65535)
        payload[0] = TpmsBleEncoding.celsius(reading).roundToInt().toByte()
        TpmsBleEncoding.writeInt16Be(payload, 1, absolutePressure)
        if (sensorId.size >= 3) {
            payload[6] = sensorId[0]
            payload[7] = sensorId[1]
            payload[8] = sensorId[2]
        }
        return TpmsBleEncoding.companyFrame(
            TpmsBleEncoding.batteryStatusCompanyId(reading),
            payload,
            TpmsAdvertisingData.ServiceUuid(
                UUID.fromString("0000a828-0000-1000-8000-00805f9b34fb")
            )
        )
    }
}

class SysgrationEncoder : TpmsManufacturerEncoder {
    override val id = "sysgration"
    override val displayName = "Sysgration"

    override fun encode(reading: TpmsReading, sensorId: ByteArray): TpmsSensorFrame {
        val payload = ByteArray(16)
        payload[0] = 0x80.toByte()
        payload[1] = 0xea.toByte()
        payload[2] = 0xca.toByte()
        System.arraycopy(sensorId.reversedArray(), 0, payload, 3, sensorId.size.coerceAtMost(3))
        val pressureMilliKpa = (TpmsBleEncoding.pressureKpa(reading) * 1000.0).roundToInt()
        val temperatureCenti = (TpmsBleEncoding.celsius(reading) * 100.0).roundToInt()
        TpmsBleEncoding.writeInt32Le(payload, 6, pressureMilliKpa.toLong())
        TpmsBleEncoding.writeInt32Le(payload, 10, temperatureCenti.toLong())
        payload[14] = (reading.batteryVoltageTenths ?: 29).toByte()
        payload[15] = if (reading.statusFlags != 0) 1 else 0
        return TpmsBleEncoding.companyFrame(
            0xffff,
            payload,
            TpmsAdvertisingData.ServiceUuid(
                UUID.fromString("0000fbb0-0000-1000-8000-00805f9b34fb")
            )
        )
    }
}

class BekubeeTpmsEncoder : TpmsManufacturerEncoder {
    override val id = "bekubee_tpms"
    override val displayName = "Bekubee TPMS (HRTPMS app)"

    override fun encode(reading: TpmsReading, sensorId: ByteArray): TpmsSensorFrame {
        val payload = ByteArray(9)
        payload[0] = TpmsBleEncoding.voltageCode(reading).toByte()
        payload[1] = (TpmsBleEncoding.celsius(reading) + 50.0).roundToInt().toByte()
        val rawPressure = (TpmsBleEncoding.pressureKpa(reading) + 100.0)
            .roundToInt()
            .coerceIn(0, 65535)
        TpmsBleEncoding.writeInt16Be(payload, 2, rawPressure)
        System.arraycopy(sensorId.reversedArray(), 0, payload, 4, sensorId.size.coerceAtMost(3))
        return TpmsBleEncoding.companyFrame(
            0x0002,
            payload,
            TpmsAdvertisingData.ServiceUuid(
                UUID.fromString("0000a827-0000-1000-8000-00805f9b34fb")
            ),
            TpmsAdvertisingData.ServiceUuid(TpmsBleProtocolIds.BEKUBEE_TPMS)
        )
    }
}
