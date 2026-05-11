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

class BLEBridge(
    private val activity: MainActivity,
    private val btAdapter: BluetoothAdapter
    ) : Bridge(activity) {
    private val prefs =
        activity.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    private val ELM_SERVICE_UUID: UUID
        get() = UUID.fromString(
            prefs.getString(
                "ble_service",
                "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
            )!!
        )

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
    private lateinit var gattServer: BluetoothGattServer
    private lateinit var advertiser: BluetoothLeAdvertiser
    private var connectedDevice: BluetoothDevice? = null
    private var txNotificationsEnabled = false
    private var gattReady = false

    private lateinit var rxChar: BluetoothGattCharacteristic
    private lateinit var txChar: BluetoothGattCharacteristic
    
    private fun sendTx(device: BluetoothDevice, text: String) {
        if (!txNotificationsEnabled) return

        val bytes = text.toByteArray(Charsets.US_ASCII)
        val mtu = 20
        var i = 0

        while (i < bytes.size) {
            val end = minOf(i + mtu, bytes.size)
            txChar.value = bytes.copyOfRange(i, end)
            gattServer.notifyCharacteristicChanged(device, txChar, false)
            i = end
        }
    }

    private val gattCallback = object : BluetoothGattServerCallback() {

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (descriptor.uuid.toString() == "00002902-0000-1000-8000-00805f9b34fb") {
                txNotificationsEnabled = value.contentEquals(byteArrayOf(0x01, 0x00))

                if (txNotificationsEnabled) {
                    sendTx(device, "ELM327 v1.5\r>")
                }
            }

            if (responseNeeded) {
                gattServer.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    null
                )
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
                connectedDevice = device
            } else {
                appendLog(getString(R.string.log_ble_disconnected, addr), LogLevel.DEBUG)
                connectedDevice = null
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
            if (characteristic.uuid == ELM_RX_UUID) {
                try {
                    loopbackOutput?.write(value)
                    loopbackOutput?.flush()
                    activity.onDataReceived(value, value.size)
                } catch(e: Exception) {
                    appendLog(getString(R.string.log_ble_gatt_characteristic_write_failed, e.message),
                        LogLevel.DEBUG
                    )
                }
            }

            if (responseNeeded) {
                gattServer.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    null
                )
            }
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
                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
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
                val bufferLoop = ByteArray(512)
                while (isActive) {
                    try {
                        val n = loopbackInput?.read(bufferLoop) ?: break
                        if (n <= 0) break
                        txChar.value = bufferLoop.copyOf(n)
                        activity.onDataSent(bufferLoop, n)
                        connectedDevice?.let {
                            gattServer.notifyCharacteristicChanged(it, txChar, false)
                        }
                    } catch(e: Exception) {
                        appendLog(getString(R.string.log_ble_loopback_failed, e.message),
                            LogLevel.DEBUG
                        )
                        break
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

        connectedDevice = null
        txNotificationsEnabled = false

        super.stop()
    }

}