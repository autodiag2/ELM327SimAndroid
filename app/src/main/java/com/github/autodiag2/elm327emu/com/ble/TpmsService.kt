package com.github.autodiag2.elm327emu.com.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothGatt
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseSettings
import android.util.Log
import com.github.autodiag2.elm327emu.BuildConfig
import com.github.autodiag2.elm327emu.EmulatorForegroundService
import com.github.autodiag2.elm327emu.R
import com.github.autodiag2.elm327emu.com.tpms.TpmsGattProfile
import com.github.autodiag2.elm327emu.com.tpms.TpmsManufacturerEncoder
import com.github.autodiag2.elm327emu.com.tpms.TpmsManufacturerEncoders
import com.github.autodiag2.elm327emu.com.tpms.TpmsManufacturerFrame
import com.github.autodiag2.elm327emu.com.tpms.TpmsReading
import com.github.autodiag2.elm327emu.log.LogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

data class TpmsSensorAdvertisement(
    val position: String,
    val companyId: Int,
    val payload: ByteArray
)

class TpmsService(
    service: EmulatorForegroundService,
    scope: CoroutineScope,
    private val onAdvertisingDataChanged: suspend (sensor: TpmsSensorAdvertisement?) -> Unit
) : BleService(service, scope) {

    companion object {
        const val DEFAULT_MANUFACTURER = "jk_tyre_treel"
        const val DEFAULT_ENABLED = false
    }

    fun logDebug(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(
                "com.ble.TpmsService",
                message
            )
        }
    }

    private data class SensorDefinition(
        val position: String,
        val sensorId: ByteArray,
        val initialReading: TpmsReading
    )

    private val sensorDefinitions = listOf(
        SensorDefinition("FL", byteArrayOf(0x00, 0x00, 0x01), TpmsReading(34, 32)),
        SensorDefinition("FR", byteArrayOf(0x00, 0x00, 0x02), TpmsReading(35, 33)),
        SensorDefinition("RL", byteArrayOf(0x00, 0x00, 0x03), TpmsReading(33, 29)),
        SensorDefinition("RR", byteArrayOf(0x00, 0x00, 0x04), TpmsReading(34, 30)),
        SensorDefinition("SP", byteArrayOf(0x00, 0x00, 0x05), TpmsReading(36, 18))
    )

    private val currentReadings = sensorDefinitions
        .associate { it.position to it.initialReading }
        .toMutableMap()

    private val random = Random.Default
    private val tpmsAdvertisementIntervalMs = 1000L

    private var tpmsAdvertisementJob: Job? = null
    private var startupNotification = false
    private var encoder: TpmsManufacturerEncoder? = null
    private var temperatureCharacteristic: BluetoothGattCharacteristic? = null
    private var pressureCharacteristic: BluetoothGattCharacteristic? = null

    val enabled: Boolean
        get() = prefs.getBoolean(TpmsManufacturerEncoders.PREF_ENABLED, DEFAULT_ENABLED)

    public val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            if (startupNotification) {
                appendLog(getString(R.string.ble_tpms_service_started), LogLevel.INFO)
                startupNotification = false
            }
        }

        override fun onStartFailure(errorCode: Int) {
            appendLog(
                getString(
                    R.string.ble_tpms_service_start_failed,
                    advertiseErrorToString(errorCode)
                ),
                LogLevel.ERROR
            )
        }
    }

    override fun getSettings(): AdvertiseSettings =
        AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(false)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

    override fun prepareStart(): Boolean = true

    override fun stop() {
        tpmsAdvertisementJob?.cancel()
        tpmsAdvertisementJob = null
        temperatureCharacteristic = null
        pressureCharacteristic = null
    }

    override fun start(): BluetoothGattService? {
        if (!enabled) return null

        encoder = TpmsManufacturerEncoders.find(
            prefs.getString(
                TpmsManufacturerEncoders.PREF_TYPE,
                DEFAULT_MANUFACTURER
            )
        )

        val activeEncoder = encoder
        if (activeEncoder == null) {
            appendLog(R.string.ble_tpms_start_fail_encoder, LogLevel.ERROR)
            return null
        }

        val initialReading = currentReadings.getValue("FL")

        val tpmsService = BluetoothGattService(
            TpmsGattProfile.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val temperatureChar = BluetoothGattCharacteristic(
            TpmsGattProfile.TEMPERATURE_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            value = byteArrayOf(initialReading.temperatureC.toByte())
        }

        val pressureChar = BluetoothGattCharacteristic(
            TpmsGattProfile.PRESSURE_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            value = byteArrayOf(initialReading.pressurePsi.toByte())
        }

        temperatureCharacteristic = temperatureChar
        pressureCharacteristic = pressureChar

        tpmsService.addCharacteristic(temperatureChar)
        tpmsService.addCharacteristic(pressureChar)

        tpmsAdvertisementJob?.cancel()
        tpmsAdvertisementJob = scope.launch(Dispatchers.IO) {
            var index = 1

            while (isActive) {
                delay(tpmsAdvertisementIntervalMs)

                val definition = sensorDefinitions[index]

                try {
                    val reading = nextReading(definition)
                    val frame = activeEncoder.encode(reading, definition.sensorId)

                    onAdvertisingDataChanged(
                        TpmsSensorAdvertisement(
                            definition.position,
                            frame.companyId,
                            frame.payload
                        )
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    appendLog(
                        getString(R.string.ble_tpms_rotation_failed, e.message),
                        LogLevel.ERROR
                    )
                }

                index = (index + 1) % sensorDefinitions.size
            }
        }

        startupNotification = true
        return tpmsService
    }

    @Synchronized
    private fun nextReading(definition: SensorDefinition): TpmsReading {
        val baseline = definition.initialReading
        val current = currentReadings.getValue(definition.position)

        val pressureMin = (baseline.pressurePsi - 2).coerceAtLeast(0)
        val pressureMax = (baseline.pressurePsi + 2).coerceAtMost(255)
        val temperatureMin = (baseline.temperatureC - 4).coerceAtLeast(-110)
        val temperatureMax = (baseline.temperatureC + 4).coerceAtMost(145)

        val next = TpmsReading(
            pressurePsi = (current.pressurePsi + random.nextInt(-1, 2))
                .coerceIn(pressureMin, pressureMax),
            temperatureC = (
                current.temperatureC +
                    if (random.nextInt(0, 4) == 0) random.nextInt(-1, 2) else 0
                ).coerceIn(temperatureMin, temperatureMax)
        )

        currentReadings[definition.position] = next
        temperatureCharacteristic?.value = byteArrayOf(next.temperatureC.toByte())
        pressureCharacteristic?.value = byteArrayOf(next.pressurePsi.toByte())

        return next
    }

    @Synchronized
    fun getSensors(): List<TpmsSensorAdvertisement> {
        if (!enabled) return emptyList()

        val activeEncoder = encoder ?: TpmsManufacturerEncoders.find(
            prefs.getString(
                TpmsManufacturerEncoders.PREF_TYPE,
                DEFAULT_MANUFACTURER
            )
        ) ?: run {
            appendLog(R.string.ble_tpms_no_sensor, LogLevel.ERROR)
            return emptyList()
        }

        return sensorDefinitions.map { definition ->
            val reading = currentReadings.getValue(definition.position)
            val frame: TpmsManufacturerFrame =
                activeEncoder.encode(reading, definition.sensorId)

            TpmsSensorAdvertisement(
                definition.position,
                frame.companyId,
                frame.payload
            )
        }
    }
}