package com.github.autodiag2.elm327emu.com.ble

import com.github.autodiag2.elm327emu.com.ble.BleService
import android.util.Log
import com.github.autodiag2.elm327emu.BuildConfig
import com.github.autodiag2.elm327emu.EmulatorForegroundService
import android.content.Context
import java.util.UUID
import android.bluetooth.*
import android.bluetooth.le.*
import com.github.autodiag2.elm327emu.log.LogLevel
import com.github.autodiag2.elm327emu.R

class Elm327Service(service: EmulatorForegroundService): BleService(service) {
    companion object {
        const val DEFAULT_UUID_SERVICE =    "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
        const val DEFAULT_UUID_RX =         "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
        const val DEFAULT_UUID_TX =         "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"
        const val CCCD_UUID       =         "00002902-0000-1000-8000-00805f9b34fb"
    }
    public lateinit var rxChar: BluetoothGattCharacteristic
    public lateinit var txChar: BluetoothGattCharacteristic
    public lateinit var gattService: BluetoothGattService
    public var txNotificationsEnabled = mutableMapOf<String, Boolean>()
    public val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    val ELM_SERVICE_UUID: UUID
        get() = UUID.fromString(
            prefs.getString(
                "com_ble_service",
                DEFAULT_UUID_SERVICE
            )!!
        )
    val ELM_RX_UUID: UUID
        get() = UUID.fromString(
            prefs.getString(
                "com_ble_rx",
                DEFAULT_UUID_RX
            )!!
        )
    val ELM_TX_UUID: UUID
        get() = UUID.fromString(
            prefs.getString(
                "com_ble_tx",
                DEFAULT_UUID_TX
            )!!
        )

    public override val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            appendLog(getString(R.string.log_ble_advertising_started), LogLevel.DEBUG)
            appendLog(getString(R.string.log_ble_server_started), LogLevel.INFO)
        }

        override fun onStartFailure(errorCode: Int) {
            appendLog(getString(R.string.log_ble_advertising_failed, advertiseErrorToString(errorCode)), LogLevel.DEBUG)
        }
    }

    override fun getSettings(): AdvertiseSettings {
        return AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build()
    }
    override fun prepareStart(): Boolean {
        try {
            ELM_SERVICE_UUID
            ELM_RX_UUID
            ELM_TX_UUID
        } catch (e: Exception) {
            appendLog(getString(R.string.log_ble_uuid_validation, e.message), LogLevel.ERROR)
            return false
        }
        return true
    }

    override fun start(): BluetoothGattService {
        gattService = BluetoothGattService(
            ELM_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        rxChar = BluetoothGattCharacteristic(
            ELM_RX_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        txChar = BluetoothGattCharacteristic(
            ELM_TX_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            0
        )
        val cccd = BluetoothGattDescriptor(
            cccdUuid,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        txChar.addDescriptor(cccd)
        gattService.addCharacteristic(rxChar)
        gattService.addCharacteristic(txChar)
        return gattService
    }

    override fun stop() {
        txNotificationsEnabled.clear()
    }

    override fun onDescriptorWriteRequest(
        device: BluetoothDevice,
        requestId: Int,
        descriptor: BluetoothGattDescriptor,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray
    ) {
        txNotificationsEnabled[device.address] = value.contentEquals(
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        )
    }

}