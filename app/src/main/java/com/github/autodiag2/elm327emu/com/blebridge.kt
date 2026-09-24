package com.github.autodiag2.elm327emu.com

import kotlinx.coroutines.*
import java.io.*
import java.util.UUID
import android.content.Context
import com.github.autodiag2.elm327emu.R
import android.bluetooth.*
import android.bluetooth.le.*
import android.os.ParcelUuid
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothGatt
import com.github.autodiag2.elm327emu.LogLevel
import com.github.autodiag2.elm327emu.MainActivity
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.os.Build
import kotlinx.coroutines.channels.Channel
import kotlin.reflect.typeOf
import androidx.annotation.RequiresApi
import com.github.autodiag2.elm327emu.sim.EmuInterface
import android.util.Log
import com.github.autodiag2.elm327emu.BuildConfig

private data class PendingRequest(
    val device: BluetoothDevice,
    val requestId: Int,
    val responseNeeded: Boolean,
    val value: ByteArray
)

class BLEBridge(
    emu: EmuInterface,
    scope: CoroutineScope,
    activity: MainActivity,
    listener: Bridge.Listener? = null
) : Bridge(emu = emu, scope = scope, activity = activity, LOG_TAG = "BLE", listener = listener) {

    private val prefs =
        activity.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    private val requestQueue = Channel<PendingRequest>(Channel.UNLIMITED)
    private val ELM_SERVICE_UUID: UUID
        get() = UUID.fromString(
            prefs.getString(
                "com_ble_service",
                "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
            )!!
        )

    private val ELM_RX_UUID: UUID
        get() = UUID.fromString(
            prefs.getString(
                "com_ble_rx",
                "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
            )!!
        )

    private val ELM_TX_UUID: UUID
        get() = UUID.fromString(
            prefs.getString(
                "com_ble_tx",
                "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"
            )!!
        )
    
    // Client Characteristic Configuration Descriptor
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private lateinit var gattServer: BluetoothGattServer
    private lateinit var advertiser: BluetoothLeAdvertiser
    private val negotiatedMtu = mutableMapOf<String, Int>()
    private var txNotificationsEnabled = mutableMapOf<String, Boolean>()
    private var gattReady = false

    private lateinit var rxChar: BluetoothGattCharacteristic
    private lateinit var txChar: BluetoothGattCharacteristic

    @Suppress("DEPRECATION")
    private fun notifyCharacteristicChangedCompat(
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gattServer.notifyCharacteristicChanged(
                device,
                characteristic,
                false,
                value
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.value = value
            gattServer.notifyCharacteristicChanged(
                device,
                characteristic,
                false
            )
        }
    }

    companion object {
        fun logDebug(message: String) {
            if (BuildConfig.DEBUG) {
                Log.d(
                    "com.blebridge",
                    message
                )
            }
        }
    }
    
    data class NotificationPacket(
        val device: BluetoothDevice,
        val data: ByteArray
    )
    private val notificationQueue = Channel<NotificationPacket>(Channel.UNLIMITED)
    private var notificationJob: Job? = null

    private fun sendTx(device: BluetoothDevice?, input: Any): Boolean {
        if (device == null) return false
        if (txNotificationsEnabled[device.address] != true) {
            return false
        }
        var bytes: Any
        if ( input is String ) {
            bytes = input.toByteArray(Charsets.US_ASCII)
        } else if ( input is ByteArray ) {
            bytes = input
        } else {
            return false
        }
        val mtu = negotiatedMtu[device.address] ?: 23
        val payloadSize = mtu - 3
        var i = 0

        while (i < bytes.size) {
            val end = minOf(i + payloadSize, bytes.size)
            val result = notificationQueue.trySend(
                NotificationPacket(device, bytes.copyOfRange(i, end))
            )

            if (result.isFailure) {
                appendLog(
                    getString(R.string.log_ble_notification_queue_failed),
                    LogLevel.ERROR
                )
                return false
            }
            i = end
        }
        if (notificationJob?.isActive != true) {
            notificationJob = scope.launch {
                for (notification in notificationQueue) {
                    appendLog(
                        getString(
                            R.string.log_ble_notification_sent,
                            notification.data.size,
                            notification.data.joinToString(" ") { "%02X".format(it) }
                        ),
                        LogLevel.DEBUG
                    )
                    if (!notifyCharacteristicChangedCompat(notification.device, txChar, notification.data)) {
                        appendLog(
                            getString(R.string.log_ble_notification_failed),
                            LogLevel.ERROR
                        )
                    }

                }
            }
        }
        return true
    }

    private val gattCallback = object : BluetoothGattServerCallback() {

        @RequiresApi(Build.VERSION_CODES.O)
        override fun onPhyUpdate(
            device: BluetoothDevice,
            txPhy: Int,
            rxPhy: Int,
            status: Int
        ) {
            appendLog(
                getString(
                    R.string.log_ble_phy_update,
                    phyToString(txPhy),
                    phyToString(rxPhy),
                    status
                ),
                LogLevel.DEBUG
            )
        }

        @RequiresApi(Build.VERSION_CODES.O)
        override fun onPhyRead(
            device: BluetoothDevice,
            txPhy: Int,
            rxPhy: Int,
            status: Int
        ) {
            appendLog(
                getString(
                    R.string.log_ble_phy_read,
                    phyToString(txPhy),
                    phyToString(rxPhy),
                    status
                ),
                LogLevel.DEBUG
            )
        }

        @RequiresApi(Build.VERSION_CODES.O)
        private fun phyToString(phy: Int): String = when (phy) {
            BluetoothDevice.PHY_LE_1M -> "1M"
            BluetoothDevice.PHY_LE_2M -> "2M"
            BluetoothDevice.PHY_LE_CODED -> "CODED"
            else -> phy.toString()
        }

        override fun onMtuChanged(
            device: BluetoothDevice,
            mtu: Int
        ) {
            logDebug("onMtuChanged")
            negotiatedMtu[device.address] = mtu
            appendLog(
                getString(
                    R.string.log_ble_mtu_changed,
                    mtu,
                    mtu - 3
                ),
                LogLevel.DEBUG
            )
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
            logDebug("onDescriptorWriteRequest")
            if (responseNeeded) {
                gattServer.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    null
                )
            }
            appendLog(
                getString(
                    R.string.log_ble_descriptor_write,
                    descriptor.uuid,
                    value.joinToString(" ") { "%02X".format(it) }
                ),
                LogLevel.DEBUG
            )
            if (descriptor.uuid == cccdUuid) {
                txNotificationsEnabled[device.address] = value.contentEquals(
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                )
            }
        }

        override fun onConnectionStateChange(
            device: BluetoothDevice,
            status: Int,
            newState: Int
        ) {
            val statusString =
                when (status) {
                    BluetoothGatt.GATT_SUCCESS ->
                        "GATT_SUCCESS"

                    8 ->
                        "GATT_CONN_TIMEOUT"

                    19 ->
                        "GATT_CONN_TERMINATE_PEER_USER"

                    22 ->
                        "GATT_CONN_TERMINATE_LOCAL_HOST"

                    34 ->
                        "GATT_LMP_TIMEOUT"

                    62 ->
                        "GATT_CONN_FAIL_ESTABLISH"

                    133 ->
                        "GATT_ERROR"

                    else ->
                        "UNKNOWN"
                }
            val addr = device.address ?: getString(R.string.log_ble_unknown_device_address)
            when(newState) {
                BluetoothProfile.STATE_CONNECTED     -> {
                    listener?.onClientConnect("${addr}", this@BLEBridge)
                    appendLog(getString(R.string.log_ble_connected, addr, status, statusString), LogLevel.INFO)
                }
                BluetoothProfile.STATE_DISCONNECTED  -> {
                    negotiatedMtu.remove(addr)
                    txNotificationsEnabled.remove(addr)
                    appendLog(getString(R.string.log_ble_disconnected, addr, status, statusString), LogLevel.INFO)
                    listener?.onClientDisconnect("${addr}", this@BLEBridge)
                }
                BluetoothProfile.STATE_CONNECTING    -> appendLog(getString(R.string.log_ble_connecting, addr, status, statusString), LogLevel.DEBUG)
                BluetoothProfile.STATE_DISCONNECTING -> appendLog(getString(R.string.log_ble_disconnecting, addr, status, statusString), LogLevel.DEBUG)
            }
        }
        
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            logDebug("onServiceAdded : ${status}")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                gattReady = true
                appendLog(getString(R.string.log_ble_gatt_service_added), LogLevel.DEBUG)
            } else {
                appendLog(getString(R.string.log_ble_gatt_service_add_failed, status),
                    LogLevel.DEBUG
                )
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            logDebug("onCharacteristicWriteRequest")
            if (characteristic.uuid != ELM_RX_UUID) {
                if (responseNeeded) {
                    gattServer.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        null
                    )
                }
                return
            }

            activity.onDataReceived(value, value.size)

            appendLog(
                getString(R.string.log_ble_request_queued, value.size),
                LogLevel.DEBUG
            )
            requestQueue.trySend(
                PendingRequest(
                    device,
                    requestId,
                    responseNeeded,
                    value.copyOf()
                )
            )

        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            appendLog(getString(R.string.log_ble_advertising_started), LogLevel.DEBUG)
            appendLog(getString(R.string.log_ble_server_started), LogLevel.INFO)
        }

        override fun onStartFailure(errorCode: Int) {
            appendLog(getString(R.string.log_ble_advertising_failed, errorCode), LogLevel.DEBUG)
        }
    }

    private fun dumpAdvertiseData(
        name: String?,
        serviceUuid: UUID?,
        includeFlags: Boolean = true
    ) {
        val bytes = ByteArrayOutputStream()

        if (includeFlags) {
            bytes.write(byteArrayOf(
                0x02,
                0x01,
                0x06
            ))
        }

        if (name != null) {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            bytes.write(nameBytes.size + 1)
            bytes.write(0x09)
            bytes.write(nameBytes)
        }

        if (serviceUuid != null) {
            val uuidBytes = ByteBuffer
                .allocate(16)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putLong(serviceUuid.leastSignificantBits)
                .putLong(serviceUuid.mostSignificantBits)
                .array()

            bytes.write(17)
            bytes.write(0x07)
            bytes.write(uuidBytes)
        }

        val payload = bytes.toByteArray()
        appendLog(getString(R.string.log_ble_advertising_data, payload.size), LogLevel.DEBUG)
        appendLog(payload.joinToString(" ") { "%02X".format(it) }, LogLevel.DEBUG)
    }

    private fun currentTimeMs(): Long {
        return System.currentTimeMillis()
    }

    override suspend fun start() {
        if (!activity.btAdapter.isEnabled) {
            activity.showBluetoothEnablePopup()
            return
        }

        try {
            ELM_SERVICE_UUID
            ELM_RX_UUID
            ELM_TX_UUID
        } catch (e: Exception) {
            appendLog(
                getString(R.string.log_ble_uuid_validation, e.message),
                LogLevel.ERROR
            )
            return
        }

        scope.launch(Dispatchers.IO) {

            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build()
            
            val advData = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build()
            
            if (!activity.btAdapter.isMultipleAdvertisementSupported) {
                appendLog(getString(R.string.log_ble_advertising_not_supported), LogLevel.WARNING)
            }

            advertiser = activity.btAdapter.bluetoothLeAdvertiser ?: run {
                appendLog(getString(R.string.log_ble_advertiser_null), LogLevel.DEBUG)
                appendLog(getString(R.string.log_ble_peripherical_mode_error), LogLevel.ERROR)
                return@launch
            }
            val scanResp = AdvertiseData.Builder()
                .addServiceUuid(ParcelUuid(ELM_SERVICE_UUID))
                .build()

            val btManager = activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            gattServer = btManager.openGattServer(activity, gattCallback)

            val service = BluetoothGattService(
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
            service.addCharacteristic(rxChar)
            service.addCharacteristic(txChar)

            appendLog(
                getString(R.string.log_ble_service_adding),
                LogLevel.DEBUG
            )
            gattServer.addService(service)
            while (!gattReady) {
                delay(10)
            }
            appendLog(
                getString(R.string.log_ble_advertising_starting),
                LogLevel.DEBUG
            )
            advertiser.startAdvertising(settings, advData, scanResp, advertiseCallback)
        }
    }

    override suspend fun accept() {

        val buffer = ByteArray(512)
        val request = requestQueue.receive()

        try {

            appendLog(
                getString(R.string.log_ble_request_processing, request.value.size),
                LogLevel.DEBUG
            )
            val n = emu.transact(
                request.value,
                request.value.size,
                buffer
            )

            if (request.responseNeeded) {
                gattServer.sendResponse(
                    request.device,
                    request.requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    null
                )
            }

            if (n > 0) {
                if ( ! sendTx(request.device, buffer.copyOf(n)) ) {
                    activity.appendLog(getString(R.string.log_ble_failed_to_send_2))
                }
                activity.onDataSent(buffer, n)
            } else {
                activity.appendLog(getString(R.string.log_ble_nothing_received), LogLevel.ERROR)
            }

        } catch (e: Exception) {

            appendLog(
                getString(
                    R.string.log_ble_loopback_failed,
                    e.message
                ),
                LogLevel.DEBUG
            )

        }
    }

    override fun stop() {
        try {
            advertiser.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            appendLog(getString(R.string.log_ble_stop_advertising_failed, e.message),
                LogLevel.DEBUG
            )
        }

        try {
            gattServer.close()
        } catch (e: Exception) {
            appendLog(getString(R.string.log_ble_gatt_server_close_failed, e.message),
                LogLevel.DEBUG
            )
        }
        gattReady = false
        txNotificationsEnabled.clear()
        negotiatedMtu.clear()
        notificationJob?.cancel()
        notificationJob = null
        clearNotificationQueue()
    }

    private fun clearNotificationQueue() {
        while (notificationQueue.tryReceive().isSuccess) {
        }
    }

}