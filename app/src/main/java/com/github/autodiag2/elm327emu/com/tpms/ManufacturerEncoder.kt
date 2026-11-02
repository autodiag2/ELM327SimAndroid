package com.github.autodiag2.elm327emu.com.tpms

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import java.util.UUID
import kotlin.math.roundToLong

data class TpmsReading(
    val pressurePsi: Int,
    val temperatureC: Int,
    val batteryPercent: Int = 100,
    val statusFlags: Int = 0,
    val pressureCentiPsi: Int? = null,
    val temperatureCentiC: Int? = null,
    val brChecksum: Int? = null,
    val batteryVoltageTenths: Int? = 29,
    val brStatusFlags: Int? = null
)

data class TpmsManufacturerFrame(
    val companyId: Int,
    val payload: ByteArray
)

sealed class TpmsAdvertisingData {
    abstract val payload: ByteArray

    data class ServiceUuid(
        val serviceUuid: UUID,
        override val payload: ByteArray = byteArrayOf()
    ) : TpmsAdvertisingData()

    data class ManufacturerData(
        val companyId: Int,
        override val payload: ByteArray
    ) : TpmsAdvertisingData()

    data class ServiceData(
        val serviceUuid: UUID,
        override val payload: ByteArray,
        val includeServiceUuid: Boolean = true
    ) : TpmsAdvertisingData()

    data class Composite(
        val items: List<TpmsAdvertisingData>,
        override val payload: ByteArray = ByteArray(0)
    ) : TpmsAdvertisingData()
}

data class TpmsSensorFrame(
    val advertisingData: TpmsAdvertisingData
)

class TpmsGattProfileInstance(
    val service: BluetoothGattService,
    private val updater: (TpmsReading) -> Unit,
    val requestCharacteristic: BluetoothGattCharacteristic? = null,
    val indicationCharacteristic: BluetoothGattCharacteristic? = null,
    private val requestHandler: ((ByteArray) -> ByteArray?)? = null
) {
    fun update(reading: TpmsReading) {
        updater(reading)
    }

    fun handleRequest(value: ByteArray): ByteArray? = requestHandler?.invoke(value)
}

interface TpmsManufacturerEncoder {
    val id: String
    val displayName: String
    val connectable: Boolean get() = false
    fun encode(reading: TpmsReading, sensorId: ByteArray): TpmsSensorFrame
    fun createGattProfiles(
        position: String,
        initialReading: TpmsReading
    ): List<TpmsGattProfileInstance> = emptyList()
}

typealias TpmsSensorProtocol = TpmsManufacturerEncoder

object TpmsManufacturerEncoders {
    const val PREF_ENABLED = "com_ble_tpms_enabled"
    const val PREF_TYPE = "com_ble_tpms_type"

    private val encoders = linkedMapOf<String, TpmsManufacturerEncoder>()

    init {
        register(JkTyreTreelEncoder())
        register(ZeepinTp630Encoder())
        register(BrTpmsEncoder())
        register(TeslaTpmsEncoder())
    }

    @Synchronized
    fun register(encoder: TpmsManufacturerEncoder) {
        require(encoder.id.isNotBlank())
        encoders[encoder.id] = encoder
    }

    @Synchronized
    fun unregister(id: String) {
        encoders.remove(id)
    }

    @Synchronized
    fun find(id: String?): TpmsManufacturerEncoder? = encoders[id]

    val all: List<TpmsManufacturerEncoder>
        get() = synchronized(this) { encoders.values.toList() }

    fun preferenceKeyForPosition(position: String): String =
        "${PREF_TYPE}_${position.lowercase()}"
}

object TpmsSensorProtocols {
    val all: List<TpmsManufacturerEncoder>
        get() = TpmsManufacturerEncoders.all

    fun register(protocol: TpmsManufacturerEncoder) = TpmsManufacturerEncoders.register(protocol)
    fun unregister(id: String) = TpmsManufacturerEncoders.unregister(id)
    fun find(id: String?): TpmsManufacturerEncoder? = TpmsManufacturerEncoders.find(id)
    fun preferenceKeyForPosition(position: String): String =
        TpmsManufacturerEncoders.preferenceKeyForPosition(position)
}

class JkTyreTreelEncoder : TpmsManufacturerEncoder {
    override val id = "jk_tyre_treel"
    override val displayName = "JK Tyre Treel"

    override fun encode(reading: TpmsReading, sensorId: ByteArray): TpmsSensorFrame {
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

        return TpmsSensorFrame(
            TpmsAdvertisingData.ManufacturerData(APPLE_COMPANY_ID, payload)
        )
    }

    companion object {
        const val APPLE_COMPANY_ID = 0x004C
        const val TEMPERATURE_OFFSET = 110
        val ADVERTISED_UUID: UUID = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffe0")
    }
}

class ZeepinTp630Encoder : TpmsManufacturerEncoder {
    override val id = "zeepin_tp630"
    override val displayName = "ZEEPIN / TP630"

    override fun encode(reading: TpmsReading, sensorId: ByteArray): TpmsSensorFrame {
        require(sensorId.size == 3) { "ZEEPIN sensorId must contain exactly 3 bytes" }
        val sensorIndex = sensorId[2].toInt() and 0xFF
        require(sensorIndex in 1..0x80) { "ZEEPIN sensor index must be between 1 and 128" }
        val sensorNumber = 0x80 + sensorIndex - 1
        val pressureMilliKpa = ((reading.pressureCentiPsi ?: reading.pressurePsi * 100) * PSI_TO_KPA * 10.0)
            .roundToLong().coerceIn(0L, 0xFFFFFFFFL)
        val temperatureCenti = (reading.temperatureCentiC ?: reading.temperatureC * 100)
            .coerceAtLeast(0).toLong().coerceAtMost(0xFFFFFFFFL)
        val payload = ByteArray(16)
        payload[0] = sensorNumber.toByte()
        payload[1] = 0xEA.toByte()
        payload[2] = 0xCA.toByte()
        System.arraycopy(sensorId, 0, payload, 3, 3)
        putUInt32Le(payload, 6, pressureMilliKpa)
        putUInt32Le(payload, 10, temperatureCenti)
        payload[14] = reading.batteryPercent.coerceIn(0, 100).toByte()
        payload[15] = reading.statusFlags.coerceIn(0, 255).toByte()
        return TpmsSensorFrame(TpmsAdvertisingData.ManufacturerData(COMPANY_ID, payload))
    }

    private fun putUInt32Le(target: ByteArray, offset: Int, value: Long) {
        for (i in 0..3) target[offset + i] = ((value ushr (i * 8)) and 0xFF).toByte()
    }

    companion object {
        const val COMPANY_ID = 0x0001
        private const val PSI_TO_KPA = 6.894757293
    }
}

class BrTpmsEncoder : TpmsManufacturerEncoder {
    override val id = "br_tpms"
    override val displayName = "BR TPMS (0x27A5)"

    override fun encode(reading: TpmsReading, sensorId: ByteArray): TpmsSensorFrame {
        val pressureAbsoluteTenthsPsi = (((reading.pressureCentiPsi ?: reading.pressurePsi * 100) / 100.0) + ATMOSPHERIC_PSI)
            .times(10.0).roundToLong().coerceIn(0L, 0xFFFFL).toInt()
        val batteryVoltageTenths = (reading.batteryVoltageTenths ?: 29).coerceIn(0, 255)
        val status = (reading.brStatusFlags ?: DEFAULT_STATUS).coerceIn(0, 255)
        val manufacturerId = (batteryVoltageTenths shl 8) or status
        val checksum = reading.brChecksum ?: 0
        val payload = byteArrayOf(
            reading.temperatureC.coerceIn(0, 255).toByte(),
            ((pressureAbsoluteTenthsPsi ushr 8) and 0xFF).toByte(),
            (pressureAbsoluteTenthsPsi and 0xFF).toByte(),
            ((checksum ushr 8) and 0xFF).toByte(),
            (checksum and 0xFF).toByte()
        )
        return TpmsSensorFrame(
            TpmsAdvertisingData.Composite(
                listOf(
                    TpmsAdvertisingData.ServiceUuid(SERVICE_UUID),
                    TpmsAdvertisingData.ManufacturerData(manufacturerId, payload)
                ),
                payload
            )
        )
    }

    companion object {
        const val DEFAULT_STATUS = 0x28
        const val DEFAULT_BATTERY_TENTHS_VOLT = 29
        private const val ATMOSPHERIC_PSI = 14.5
        val SERVICE_UUID: UUID = UUID.fromString("000027a5-0000-1000-8000-00805f9b34fb")
    }
}


class TeslaTpmsEncoder : TpmsManufacturerEncoder {
    override val id = "tesla_ble"
    override val displayName = "Tesla / Autel BLE"
    override val connectable = true

    override fun encode(reading: TpmsReading, sensorId: ByteArray): TpmsSensorFrame {
        val pressureKpa = ((reading.pressureCentiPsi ?: reading.pressurePsi * 100) / 100.0 * PSI_TO_KPA + ATMOSPHERE_KPA)
            .roundToLong().coerceIn(0L, 100000L).toInt()
        val temperature = ((reading.temperatureCentiC ?: reading.temperatureC * 100) / 100.0)
            .roundToLong().toInt().coerceIn(-50, 150)
        val batteryMv = ((reading.batteryVoltageTenths ?: 29) * 100).coerceIn(1500, 4300)
        val payload = byteArrayOf(
            0x01,
            0x02,
            0x05,
            (pressureKpa and 0xFF).toByte(),
            ((pressureKpa ushr 8) and 0xFF).toByte(),
            (temperature + 50).toByte(),
            (batteryMv and 0xFF).toByte(),
            ((batteryMv ushr 8) and 0xFF).toByte()
        )
        return TpmsSensorFrame(
            TpmsAdvertisingData.ManufacturerData(TESLA_COMPANY_ID, payload)
        )
    }

    override fun createGattProfiles(
        position: String,
        initialReading: TpmsReading
    ): List<TpmsGattProfileInstance> {
        val service = BluetoothGattService(
            SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        val writeCharacteristic = BluetoothGattCharacteristic(
            WRITE_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val indicationCharacteristic = BluetoothGattCharacteristic(
            INDICATE_UUID,
            BluetoothGattCharacteristic.PROPERTY_INDICATE,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        indicationCharacteristic.addDescriptor(
            BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        val versionCharacteristic = BluetoothGattCharacteristic(
            VERSION_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply { value = byteArrayOf(1) }
        service.addCharacteristic(writeCharacteristic)
        service.addCharacteristic(indicationCharacteristic)
        service.addCharacteristic(versionCharacteristic)
        var current = initialReading
        fun response(reading: TpmsReading): ByteArray {
            val psi = (reading.pressureCentiPsi ?: reading.pressurePsi * 100) / 100.0
            val pressure = (psi * PSI_TO_KPA + ATMOSPHERE_KPA).roundToLong().toInt()
            val temperature = ((reading.temperatureCentiC ?: reading.temperatureC * 100) / 100.0)
                .roundToLong().toInt()
            val zigzag = (temperature shl 1) xor (temperature shr 31)
            val body = encodeVarint(0x08) + encodeVarint(pressure) +
                encodeVarint(0x10) + encodeVarint(zigzag)
            val wheel = encodeLengthDelimited(0xE2, body)
            return encodeLengthDelimited(0x12, wheel)
        }
        indicationCharacteristic.value = response(current)
        return listOf(
            TpmsGattProfileInstance(
                service = service,
                updater = { reading ->
                    current = reading
                    indicationCharacteristic.value = response(reading)
                },
                requestCharacteristic = writeCharacteristic,
                indicationCharacteristic = indicationCharacteristic,
                requestHandler = { request ->
                    if (isTpDataRequest(request)) response(current) else null
                }
            )
        )
    }

    private fun isTpDataRequest(value: ByteArray): Boolean {
        var i = 0
        while (i + 1 < value.size) {
            if (value[i] == 0x08.toByte() && value[i + 1] == 0x01.toByte()) return true
            i++
        }
        return false
    }

    private fun encodeLengthDelimited(tag: Int, value: ByteArray): ByteArray =
        encodeVarint(tag) + encodeVarint(value.size) + value

    private fun encodeVarint(input: Int): ByteArray {
        var value = input
        val result = ArrayList<Byte>()
        while (value and 0x7F.inv() != 0) {
            result.add(((value and 0x7F) or 0x80).toByte())
            value = value ushr 7
        }
        result.add(value.toByte())
        return result.toByteArray()
    }

    companion object {
        const val TESLA_COMPANY_ID = 0x022B
        const val PREF_POSITION = "com_ble_tpms_tesla_position"
        private const val PSI_TO_KPA = 6.894757293
        private const val ATMOSPHERE_KPA = 101.325
        val SERVICE_UUID: UUID = UUID.fromString("00000211-b2d1-43f0-9b88-960cebf8b91e")
        val WRITE_UUID: UUID = UUID.fromString("00000212-b2d1-43f0-9b88-960cebf8b91e")
        val INDICATE_UUID: UUID = UUID.fromString("00000213-b2d1-43f0-9b88-960cebf8b91e")
        val VERSION_UUID: UUID = UUID.fromString("00000214-b2d1-43f0-9b88-960cebf8b91e")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

object TpmsGattProfile {
    val SERVICE_UUID: UUID = UUID.fromString("9f2c0001-75d8-4e7d-a28c-9b3b6c17e001")
    val TEMPERATURE_UUID: UUID = UUID.fromString("9f2c0002-75d8-4e7d-a28c-9b3b6c17e001")
    val PRESSURE_UUID: UUID = UUID.fromString("9f2c0003-75d8-4e7d-a28c-9b3b6c17e001")

    fun create(initialReading: TpmsReading): TpmsGattProfileInstance {
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val temperatureCharacteristic = BluetoothGattCharacteristic(
            TEMPERATURE_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply { value = byteArrayOf(initialReading.temperatureC.toByte()) }
        val pressureCharacteristic = BluetoothGattCharacteristic(
            PRESSURE_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply { value = byteArrayOf(initialReading.pressurePsi.toByte()) }
        service.addCharacteristic(temperatureCharacteristic)
        service.addCharacteristic(pressureCharacteristic)
        return TpmsGattProfileInstance(service) { reading ->
            temperatureCharacteristic.value = byteArrayOf(reading.temperatureC.toByte())
            pressureCharacteristic.value = byteArrayOf(reading.pressurePsi.toByte())
        }
    }
}
