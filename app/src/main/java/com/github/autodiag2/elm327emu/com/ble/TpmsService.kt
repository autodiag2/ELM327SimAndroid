package com.github.autodiag2.elm327emu.com.ble

import com.github.autodiag2.elm327emu.com.ble.BleService
import android.util.Log
import com.github.autodiag2.elm327emu.BuildConfig
import com.github.autodiag2.elm327emu.EmulatorForegroundService
import android.content.Context
import java.util.UUID
import android.bluetooth.*
import android.bluetooth.le.*
import com.github.autodiag2.elm327emu.com.tpms.*
import com.github.autodiag2.elm327emu.log.LogLevel
import com.github.autodiag2.elm327emu.R
import kotlinx.coroutines.*

data class TpmsSensorAdvertisement(
    val position: String,
    val companyId: Int,
    val payload: ByteArray
)

class TpmsService(service: EmulatorForegroundService, scope: CoroutineScope, private val onAdvertisingDataChanged: (suspend (sensor: TpmsSensorAdvertisement?) -> Unit)): BleService(service, scope) {
    companion object {
        
    }
    
    val enabled: Boolean
        get() = prefs.getBoolean(TpmsManufacturerEncoders.PREF_ENABLED, false)

    private var tpmsAdvertisementJob: Job? = null
    private val tpmsAdvertisementIntervalMs = 1000L
    private var startupNotification = false

    public override val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            if ( startupNotification ) {
                appendLog("TPMS advertisement started", LogLevel.INFO)
                startupNotification = false
            }
        }

        override fun onStartFailure(errorCode: Int) {
            appendLog("TPMS advertisement failed: ${advertiseErrorToString(errorCode)}", LogLevel.ERROR)
        }
    }

    override fun getSettings(): AdvertiseSettings {
        return AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(false)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build()
    }

    override fun prepareStart(): Boolean {
        return true
    }

    private var encoder: TpmsManufacturerEncoder? = null

    override fun stop() {
        tpmsAdvertisementJob?.cancel()
        tpmsAdvertisementJob = null
    }

    override fun start(): BluetoothGattService? {
        if ( ! enabled ) return null
        encoder = 
            TpmsManufacturerEncoders.find(
                prefs.getString(TpmsManufacturerEncoders.PREF_TYPE, "jk_tyre_treel")
            )
        if ( encoder == null) {
            appendLog("Unsupported TPMS manufacturer/model", LogLevel.ERROR)
            return null
        }
        val tpmsService = BluetoothGattService(
            TpmsGattProfile.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        val temperatureChar = BluetoothGattCharacteristic(
            TpmsGattProfile.TEMPERATURE_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            value = byteArrayOf(TpmsGattProfile.TEST_VALUE.toByte())
        }
        val pressureChar = BluetoothGattCharacteristic(
            TpmsGattProfile.PRESSURE_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            value = byteArrayOf(TpmsGattProfile.TEST_VALUE.toByte())
        }
        tpmsService.addCharacteristic(temperatureChar)
        tpmsService.addCharacteristic(pressureChar)

        val tpmsSensors = getSensors()
        tpmsAdvertisementJob?.cancel()
        tpmsAdvertisementJob = scope.launch(Dispatchers.IO) {
            var index = 1
            while (isActive) {
                delay(tpmsAdvertisementIntervalMs)
                val sensor = tpmsSensors[index]
                try {
                    onAdvertisingDataChanged.invoke(sensor)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    appendLog("TPMS advertisement rotation failed: ${e.message}", LogLevel.ERROR)
                }
                index = (index + 1) % tpmsSensors.size
            }
        }
        startupNotification = true
        return tpmsService
    }

    fun getSensors(): List<TpmsSensorAdvertisement> {
        if ( ! enabled ) return listOf()
        val tpmsSensors: List<TpmsSensorAdvertisement> = encoder?.let { encoder ->
            val reading = TpmsReading(pressurePsi = 55, temperatureC = 55)
            listOf(
                "FL" to byteArrayOf(0x00, 0x00, 0x01),
                "FR" to byteArrayOf(0x00, 0x00, 0x02),
                "RL" to byteArrayOf(0x00, 0x00, 0x03),
                "RR" to byteArrayOf(0x00, 0x00, 0x04),
                "SP" to byteArrayOf(0x00, 0x00, 0x05)
            ).mapNotNull { (position, sensorId) ->
                encoder.encode(reading, sensorId).let { frame ->
                    TpmsSensorAdvertisement(position, frame.companyId, frame.payload)
                }
            }
        }.orEmpty()
        if ( tpmsSensors.isEmpty() ) {
            appendLog("TPMS encoder did not produce any sensor frames", LogLevel.ERROR)
            return listOf()
        }
        return tpmsSensors
    }

}