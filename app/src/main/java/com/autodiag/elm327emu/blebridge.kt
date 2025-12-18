package com.autodiag.elm327emu

import android.Manifest
import androidx.appcompat.app.AppCompatActivity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import kotlinx.coroutines.*
import java.io.*
import java.net.Socket
import java.util.UUID
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.autodiag.elm327emu.libautodiag
import android.util.Log
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.widget.FrameLayout
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import android.view.MenuItem
import android.content.Intent
import com.autodiag.elm327emu.SimGeneratorGui
import androidx.appcompat.widget.Toolbar
import android.content.Context
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ListView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Button
import android.widget.CheckBox
import android.widget.ScrollView
import androidx.core.widget.addTextChangedListener
import com.autodiag.elm327emu.R
import android.view.ViewGroup.LayoutParams
import android.view.View
import android.content.SharedPreferences
import android.widget.*
import android.view.MotionEvent
import android.bluetooth.*
import android.bluetooth.le.*
import android.os.ParcelUuid
import java.io.InputStream
import java.io.OutputStream
import android.bluetooth.BluetoothManager
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BLEBridge(
    private val activity: MainActivity,
    private val btAdapter: BluetoothAdapter
    ) {
    private val ELM_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private val ELM_RX_UUID      = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    private val ELM_TX_UUID      = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    private lateinit var gattServer: BluetoothGattServer
    private lateinit var advertiser: BluetoothLeAdvertiser
    private var connectedDevice: BluetoothDevice? = null
    private var txNotificationsEnabled = false
    private var gattReady = false

    private lateinit var rxChar: BluetoothGattCharacteristic
    private lateinit var txChar: BluetoothGattCharacteristic

    private var loopbackInput: InputStream? = null
    private var loopbackOutput: OutputStream? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun appendLog(level: LogLevel, text: String) {
        activity.appendLog(level, text)
    }
    
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
            val addr = device.address ?: "unknown"
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                appendLog(LogLevel.DEBUG, "$addr: connected")
                connectedDevice = device
            } else {
                appendLog(LogLevel.DEBUG, "$addr: disconnected")
                connectedDevice = null
            }
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                gattReady = true
                appendLog(LogLevel.DEBUG, "GATT service added")
            } else {
                appendLog(LogLevel.DEBUG, "Service add failed: $status")
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
                    appendLog(LogLevel.DEBUG, " * Received from Bluetooth: (passing to loopback)")
                    appendLog(LogLevel.DEBUG, hexDump(value, value.size))
                } catch(e: Exception) {
                    appendLog(LogLevel.DEBUG, "exiting btToLoop: ${e.message}")
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
            appendLog(LogLevel.DEBUG, "BLE advertising started")
        }

        override fun onStartFailure(errorCode: Int) {
            appendLog(LogLevel.DEBUG, "BLE advertising failed: $errorCode")
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
        appendLog(LogLevel.DEBUG, "ADV length = ${payload.size}")
        appendLog(LogLevel.DEBUG, payload.joinToString(" ") { "%02X".format(it) })
    }

    public fun start() {
        if (!btAdapter.isEnabled) {
            activity.showBluetoothEnablePopup()
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
                appendLog(LogLevel.DEBUG, "BLE advertising not supported")
                return@launch
            }

            advertiser = btAdapter.bluetoothLeAdvertiser ?: run {
                appendLog(LogLevel.DEBUG, "bluetoothLeAdvertiser == null")
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

            val location = libautodiag.launchEmu(activity.filesDir.absolutePath)
            val loopSock = LocalSocket()
            loopSock.connect(LocalSocketAddress(location, LocalSocketAddress.Namespace.FILESYSTEM))
            loopbackInput = loopSock.inputStream
            loopbackOutput = loopSock.outputStream

            launch {
                val bufferLoop = ByteArray(512)
                while (isActive) {
                    try {
                        val n = loopbackInput?.read(bufferLoop) ?: break
                        if (n <= 0) break
                        txChar.value = bufferLoop.copyOf(n)
                        appendLog(LogLevel.DEBUG, " * Sending the data received from loopback on bluetooth:")
                        appendLog(LogLevel.DEBUG, hexDump(bufferLoop, n))
                        connectedDevice?.let {
                            gattServer.notifyCharacteristicChanged(it, txChar, false)
                        }
                    } catch(e: Exception) {
                        appendLog(LogLevel.DEBUG, "exiting loopToBt: ${e.message}")
                        break
                    }
                }
            }
        }
    }

    public fun stop() {
        try {
            advertiser.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            appendLog(LogLevel.DEBUG, "Error: ${e.message}")
        }

        try {
            gattServer.close()
        } catch (e: Exception) {
            appendLog(LogLevel.DEBUG, "Error: ${e.message}")
        }

        connectedDevice = null
        txNotificationsEnabled = false
    }

}