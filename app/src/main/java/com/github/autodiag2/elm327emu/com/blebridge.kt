package com.github.autodiag2.elm327emu.com

import kotlinx.coroutines.*
import java.io.*
import java.util.UUID
import android.bluetooth.BluetoothAdapter
import android.content.Context
import com.github.autodiag2.elm327emu.R
import android.bluetooth.*
import android.bluetooth.le.*
import android.os.ParcelUuid
import android.bluetooth.BluetoothManager
import com.github.autodiag2.elm327emu.LogLevel
import com.github.autodiag2.elm327emu.MainActivity
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.os.Build
import kotlinx.coroutines.channels.Channel
import kotlin.reflect.typeOf

private data class PendingRequest(
    val device: BluetoothDevice,
    val requestId: Int,
    val responseNeeded: Boolean,
    val value: ByteArray
)

class BLEBridge(
    private val activity: MainActivity,
    private val btAdapter: BluetoothAdapter
    ) : Bridge(activity) {
    private val prefs =
        activity.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    private val requestQueue = Channel<PendingRequest>(Channel.UNLIMITED)
    private val ELM_SERVICE_UUID: UUID
        get() = UUID.fromString(
            prefs.getString(
                "ble_service",
                "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
            )!!
        )
    private var negotiatedMtu = 23

    private val ELM_RX_UUID: UUID
        get() = UUID.fromString(
            prefs.getString(
                "ble_rx",
                "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
            )!!
        )

    private val ELM_TX_UUID: UUID
        get() = UUID.fromString(
            prefs.getString(
                "ble_tx",
                "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"
            )!!
        )
    
    // Client Characteristic Configuration Descriptor
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private lateinit var gattServer: BluetoothGattServer
    private lateinit var advertiser: BluetoothLeAdvertiser
    private var txNotificationsEnabled = false
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
    
    private fun sendTx(device: BluetoothDevice?, input: Any): Boolean {
        if (!txNotificationsEnabled) return false
        if (device == null) return false

        var bytes: Any
        if ( input is String ) {
            bytes = input.toByteArray(Charsets.US_ASCII)
        } else if ( input is ByteArray ) {
            bytes = input
        } else {
            return false
        }
        val payloadSize = negotiatedMtu - 3
        var i = 0

        while (i < bytes.size) {
            val end = minOf(i + payloadSize, bytes.size)
            if ( ! notifyCharacteristicChangedCompat(device, txChar, bytes.copyOfRange(i, end)) ) {
                return false
            }
            i = end
        }
        return true
    }

    private val gattCallback = object : BluetoothGattServerCallback() {

        override fun onMtuChanged(
            device: BluetoothDevice,
            mtu: Int
        ) {
            negotiatedMtu = mtu
            appendLog("BLE MTU changed to $mtu", LogLevel.DEBUG)
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
            if (responseNeeded) {
                gattServer.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    null
                )
            }
            
            if (descriptor.uuid == cccdUuid) {
                txNotificationsEnabled = value.contentEquals(byteArrayOf(0x01, 0x00))

                if (txNotificationsEnabled) {
                    if ( ! sendTx(device, "ELM327 v1.5\r>") ) {
                        activity.appendLog("failed to send (1)")
                    }
                }
            }
        }

        override fun onConnectionStateChange(
            device: BluetoothDevice,
            status: Int,
            newState: Int
        ) {
            val addr = device.address ?: getString(R.string.log_ble_unknown_device_address)
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                appendLog(getString(R.string.log_ble_connected, addr), LogLevel.DEBUG)
            } else {
                appendLog(getString(R.string.log_ble_disconnected, addr), LogLevel.DEBUG)
            }
        }
        
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
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

    override fun start() {
        if (!btAdapter.isEnabled) {
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
            activity.clearSocketFiles()

            advertiser = btAdapter.bluetoothLeAdvertiser
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build()
            
            val advData = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build()
            
            if (!btAdapter.isMultipleAdvertisementSupported) {
                appendLog(getString(R.string.log_ble_advertising_not_supported), LogLevel.DEBUG)
                return@launch
            }

            advertiser = btAdapter.bluetoothLeAdvertiser ?: run {
                appendLog(getString(R.string.log_ble_advertiser_null), LogLevel.DEBUG)
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
                BluetoothGattCharacteristic.PERMISSION_READ
            )

            val cccd = BluetoothGattDescriptor(
                cccdUuid,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )

            txChar.addDescriptor(cccd)
            service.addCharacteristic(rxChar)
            service.addCharacteristic(txChar)

            gattServer.addService(service)
            while (!gattReady) {
                delay(10)
            }
            advertiser.startAdvertising(settings, advData, scanResp, advertiseCallback)

            emuStart()

            launch {

                val buffer = ByteArray(512)

                while (isActive) {

                    val request = requestQueue.receive()

                    try {

                        emuSend(
                            request.value,
                            request.value.size
                        )

                        val n = emuRecv(buffer)

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
                                activity.appendLog("failed to send (2)")
                            }
                            activity.onDataSent(buffer, n)
                        } else {
                            activity.appendLog("Nothing received from emu", LogLevel.ERROR)
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

            }
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

        txNotificationsEnabled = false

        super.stop()
    }

}