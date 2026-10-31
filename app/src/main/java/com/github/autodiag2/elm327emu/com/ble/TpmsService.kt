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

class TpmsService(service: EmulatorForegroundService): BleService(service) {
    companion object {
        
    }

    public override val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            appendLog("TPMS advertisement started", LogLevel.INFO)
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

    override fun start(): BluetoothGattService {
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
        return tpmsService
    }

}