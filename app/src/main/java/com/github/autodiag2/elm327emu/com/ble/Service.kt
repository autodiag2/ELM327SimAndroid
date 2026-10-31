package com.github.autodiag2.elm327emu.com.ble

import android.util.Log
import com.github.autodiag2.elm327emu.BuildConfig
import com.github.autodiag2.elm327emu.EmulatorForegroundService
import android.content.Context
import java.util.UUID
import com.github.autodiag2.elm327emu.log.LogLevel
import android.bluetooth.*
import android.bluetooth.le.*
import kotlinx.coroutines.*

abstract class BleService(public val service: EmulatorForegroundService, public val scope: CoroutineScope) {
    protected val prefs = service.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    protected fun appendLog(text: Any, level: LogLevel = LogLevel.DEBUG) {
        service.log.append(text, level)
    }

    protected fun getString(resId: Int, vararg formatArgs: Any?): String {
        return service.getString(resId, *formatArgs.map { it ?: "" }.toTypedArray())
    }

    abstract fun getSettings(): AdvertiseSettings
    abstract fun prepareStart(): Boolean
    abstract fun start(): BluetoothGattService?

    open fun stop() {

    }

    open fun onDescriptorWriteRequest(
        device: BluetoothDevice,
        requestId: Int,
        descriptor: BluetoothGattDescriptor,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray
    ) {

    }

    protected fun advertiseErrorToString(errorCode: Int): String {
        return when (errorCode) {
            AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE ->
                "ADVERTISE_FAILED_DATA_TOO_LARGE"
            AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS ->
                "ADVERTISE_FAILED_TOO_MANY_ADVERTISERS"
            AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED ->
                "ADVERTISE_FAILED_ALREADY_STARTED"
            AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR ->
                "ADVERTISE_FAILED_INTERNAL_ERROR"
            AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED ->
                "ADVERTISE_FAILED_FEATURE_UNSUPPORTED"
            else ->
                "UNKNOWN($errorCode)"
        }
    }
}