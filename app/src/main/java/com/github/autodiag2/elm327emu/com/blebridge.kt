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
import com.github.autodiag2.elm327emu.log.LogLevel
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
import com.github.autodiag2.elm327emu.EmulatorForegroundService
import com.github.autodiag2.elm327emu.com.tpms.*
import com.github.autodiag2.elm327emu.com.ble.Elm327Service
import com.github.autodiag2.elm327emu.com.ble.TpmsService
import com.github.autodiag2.elm327emu.com.ble.TpmsSensorAdvertisement

private data class PendingRequest(
    val device: BluetoothDevice,
    val requestId: Int,
    val responseNeeded: Boolean,
    val value: ByteArray
)

class BLEBridge(
    emuProvider: EmuInterface.Provider,
    scope: CoroutineScope,
    service: EmulatorForegroundService,
    listener: Bridge.Listener? = null
) : Bridge(emuProvider = emuProvider, scope = scope, service = service, LOG_TAG = "BLE", listener = listener) {
    private val prefs = service.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    private val requestQueue = Channel<PendingRequest>(Channel.UNLIMITED)

    init {
        prefs.edit()
            .putBoolean(TpmsManufacturerEncoders.PREF_ENABLED, true)
            .putString(TpmsManufacturerEncoders.PREF_TYPE, "jk_tyre_treel")
            .apply()
    }
    // Client Characteristic Configuration Descriptor
    private lateinit var gattServer: BluetoothGattServer
    private lateinit var advertiser: BluetoothLeAdvertiser
    private val negotiatedMtu = mutableMapOf<String, Int>()
    private var gattReady = false
    private var pendingGattServiceUuid: UUID? = null
    private var gattServiceAddResult: Boolean? = null
    private val elm327Service: Elm327Service = Elm327Service(service, scope)
    private val tpmsService: TpmsService = TpmsService(service, scope, ::onAdvertisingDataChanged)

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
        val getScanResponseAvailableSpace: Int = 29
    }
    data class NotificationPacket(
        val device: BluetoothDevice,
        val data: ByteArray
    )
    private val notificationQueue = Channel<NotificationPacket>(Channel.UNLIMITED)
    private var notificationJob: Job? = null

    private fun sendTx(device: BluetoothDevice?, input: Any): Boolean {
        if (device == null) return false
        if (elm327Service.txNotificationsEnabled[device.address] != true) {
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
                    if (!notifyCharacteristicChangedCompat(notification.device, elm327Service.txChar, notification.data)) {
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
            if (descriptor.uuid == elm327Service.cccdUuid) {
                elm327Service.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value)
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
                    elm327Service.txNotificationsEnabled.remove(addr)
                    appendLog(getString(R.string.log_ble_disconnected, addr, status, statusString), LogLevel.INFO)
                    listener?.onClientDisconnect("${addr}", this@BLEBridge)
                }
                BluetoothProfile.STATE_CONNECTING    -> appendLog(getString(R.string.log_ble_connecting, addr, status, statusString), LogLevel.DEBUG)
                BluetoothProfile.STATE_DISCONNECTING -> appendLog(getString(R.string.log_ble_disconnecting, addr, status, statusString), LogLevel.DEBUG)
            }
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            logDebug("onServiceAdded : $status : ${service.uuid}")
            if (service.uuid == pendingGattServiceUuid) {
                gattServiceAddResult = status == BluetoothGatt.GATT_SUCCESS
                gattReady = true
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                appendLog(getString(R.string.log_ble_gatt_service_added), LogLevel.DEBUG)
            } else {
                appendLog(getString(R.string.log_ble_gatt_service_add_failed, status), LogLevel.ERROR)
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
            if (characteristic.uuid != elm327Service.ELM_RX_UUID) {
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
            service.onDataReceived(value, value.size)
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

    private suspend fun addGattService(gattService: BluetoothGattService): Boolean {
        pendingGattServiceUuid = gattService.uuid
        gattReady = false
        gattServiceAddResult = null
        if (!gattServer.addService(gattService)) {
            pendingGattServiceUuid = null
            appendLog("GATT addService() failed: ${gattService.uuid}", LogLevel.ERROR)
            return false
        }
        return try {
            withTimeout(5000L) {
                while (!gattReady) delay(10L)
            }
            gattServiceAddResult == true
        } catch (_: TimeoutCancellationException) {
            appendLog("Timed out adding GATT service ${gattService.uuid}", LogLevel.ERROR)
            false
        } finally {
            pendingGattServiceUuid = null
            gattReady = false
        }
    }

    override suspend fun start() {
        if (!service.btAdapter.isEnabled) {
            EmulatorForegroundService.activity?.showBluetoothEnablePopup()
            return
        }
        if ( ! elm327Service.prepareStart() ) {
            return
        }
        if ( ! tpmsService.prepareStart() ) {
            return
        }
        scope.launch(Dispatchers.IO) {
            advertiser = service.btAdapter.bluetoothLeAdvertiser ?: run {
                appendLog(getString(R.string.log_ble_advertiser_null), LogLevel.DEBUG)
                appendLog(getString(R.string.log_ble_peripherical_mode_error), LogLevel.ERROR)
                return@launch
            }

            val btManager = service.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            gattServer = btManager.openGattServer(service, gattCallback)
            val elmService = elm327Service.start()
            appendLog(getString(R.string.log_ble_service_adding), LogLevel.DEBUG)
            if (elmService == null || !addGattService(elmService)) return@launch
            if (tpmsService.enabled) {
                val gattService = tpmsService.start()
                if ( gattService == null || !addGattService(gattService)) {
                    appendLog("TPMS GATT service could not be added; continuing with Treel advertisement", LogLevel.ERROR)
                }
            }
            appendLog(getString(R.string.log_ble_advertising_starting), LogLevel.DEBUG)
            val tpmsSensors = tpmsService.getSensors()
            val firstTpmsFrame = tpmsSensors.firstOrNull()
            if (!service.btAdapter.isMultipleAdvertisementSupported) {
                appendLog(getString(R.string.log_ble_advertising_not_supported), LogLevel.WARNING)
            }
            onAdvertisingDataChanged(firstTpmsFrame)
        }
    }

    fun onAdvertisingDataChanged(tpmsSensor: TpmsSensorAdvertisement?) {
        val settings = elm327Service.getSettings()
        val tpmsSettings = tpmsService.getSettings()
        if ( service.btAdapter.isMultipleAdvertisementSupported ) {
            val advData =
                AdvertiseData.Builder()
                    .addServiceUuid(ParcelUuid(elm327Service.ELM_SERVICE_UUID))
                    .build()
            val scanResp = 
                AdvertiseData.Builder()
                    .setIncludeDeviceName(true)
                    .build()
            advertiser.stopAdvertising(elm327Service.advertiseCallback)
            advertiser.startAdvertising(settings, advData, scanResp, elm327Service.advertiseCallback)
            if ( tpmsService.enabled && tpmsSensor != null ) {
                val tpmsAdvData =
                    AdvertiseData.Builder()
                        .addManufacturerData(tpmsSensor.companyId, tpmsSensor.payload)
                        .build()
                val tpmsScanResp =
                    AdvertiseData.Builder().build()
                advertiser.startAdvertising(tpmsSettings, tpmsAdvData, tpmsScanResp, tpmsService.advertiseCallback)
                advertiser.stopAdvertising(tpmsService.advertiseCallback)
            }
        } else {
            val builder = AdvertiseData.Builder()
            builder.addServiceUuid(ParcelUuid(elm327Service.ELM_SERVICE_UUID))
            if ( tpmsService.enabled && tpmsSensor != null ) {
                builder.addManufacturerData(tpmsSensor.companyId, tpmsSensor.payload)
            }
            val advData = builder.build()
            val scanResp = 
                AdvertiseData.Builder()
                    .setIncludeDeviceName(true)
                    .build()
            advertiser.stopAdvertising(elm327Service.advertiseCallback)
            advertiser.startAdvertising(settings, advData, scanResp, elm327Service.advertiseCallback)
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
            val n = emuProvider.getEmu().transact(
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
                    service.log.append(getString(R.string.log_ble_failed_to_send_2))
                }
                service.onDataSent(buffer, n)
            } else {
                service.log.append(getString(R.string.log_ble_nothing_received), LogLevel.ERROR)
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
            advertiser.stopAdvertising(tpmsService.advertiseCallback)
        } catch (e: Exception) {
            logDebug("Unable to stop TPMS advertising: ${e.message}")
        }
        try {
            advertiser.stopAdvertising(elm327Service.advertiseCallback)
        } catch (e: Exception) {
            appendLog(getString(R.string.log_ble_stop_advertising_failed, e.message),
                LogLevel.DEBUG
            )
        }
        try {
            gattServer.clearServices()
            gattServer.close()
        } catch (e: Exception) {
            appendLog(getString(R.string.log_ble_gatt_server_close_failed, e.message),
                LogLevel.DEBUG
            )
        }
        clearNotificationQueue()
        notificationJob?.cancel()
        notificationJob = null
        gattReady = false
        elm327Service.stop()
        tpmsService.stop()
        negotiatedMtu.clear()
    }

    private fun clearNotificationQueue() {
        while (notificationQueue.tryReceive().isSuccess) {
        }
    }
}
