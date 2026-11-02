package com.github.autodiag2.elm327emu.com.ble

import android.bluetooth.BluetoothGattService
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.os.ParcelUuid
import android.util.Log
import com.github.autodiag2.elm327emu.BuildConfig
import com.github.autodiag2.elm327emu.EmulatorForegroundService
import com.github.autodiag2.elm327emu.R
import com.github.autodiag2.elm327emu.com.tpms.TpmsAdvertisingData
import com.github.autodiag2.elm327emu.com.tpms.TpmsSensorFrame
import com.github.autodiag2.elm327emu.com.tpms.TpmsGattProfile
import com.github.autodiag2.elm327emu.com.tpms.TpmsGattProfileInstance
import com.github.autodiag2.elm327emu.com.tpms.TpmsManufacturerEncoder
import com.github.autodiag2.elm327emu.com.tpms.TpmsManufacturerEncoders
import com.github.autodiag2.elm327emu.com.tpms.TpmsReading
import com.github.autodiag2.elm327emu.com.tpms.TpmsSensorConfiguration
import com.github.autodiag2.elm327emu.log.LogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.random.Random

data class TpmsSensorAdvertisement(
    val position: String,
    val frame: TpmsSensorFrame
) {
    val payload: ByteArray
        get() = frame.advertisingData.payload

    val companyId: Int?
        get() = when (val data = frame.advertisingData) {
            is TpmsAdvertisingData.ManufacturerData -> data.companyId
            is TpmsAdvertisingData.Composite -> data.items.filterIsInstance<TpmsAdvertisingData.ManufacturerData>().firstOrNull()?.companyId
            else -> null
        }

    fun addTo(builder: AdvertiseData.Builder): AdvertiseData.Builder {
        addAdvertisingData(builder, frame.advertisingData)
        return builder
    }

    fun toAdvertiseData(): AdvertiseData = addTo(AdvertiseData.Builder()).build()

    private fun addAdvertisingData(builder: AdvertiseData.Builder, data: TpmsAdvertisingData) {
        when (data) {
            is TpmsAdvertisingData.ServiceUuid -> builder.addServiceUuid(ParcelUuid(data.serviceUuid))
            is TpmsAdvertisingData.ManufacturerData -> builder.addManufacturerData(data.companyId, data.payload)
            is TpmsAdvertisingData.ServiceData -> {
                val parcelUuid = ParcelUuid(data.serviceUuid)
                if (data.includeServiceUuid) builder.addServiceUuid(parcelUuid)
                builder.addServiceData(parcelUuid, data.payload)
            }
            is TpmsAdvertisingData.Composite -> data.items.forEach { addAdvertisingData(builder, it) }
        }
    }
}

class TpmsService(
    service: EmulatorForegroundService,
    scope: CoroutineScope,
    private val onAdvertisingDataChanged: suspend (sensor: TpmsSensorAdvertisement?) -> Unit
) : BleService(service, scope) {

    companion object {
        const val DEFAULT_MANUFACTURER = "jk_tyre_treel"
        const val DEFAULT_ENABLED = false
        const val DEFAULT_ADVERTISEMENT_INTERVAL_MS = 1000L
    }

    fun logDebug(message: String) {
        if (BuildConfig.DEBUG) Log.d("com.ble.TpmsService", message)
    }

    data class SensorDefinition(
        val position: String,
        val sensorId: ByteArray,
        val initialReading: TpmsReading
    )

    val sensorDefinitions = listOf(
        SensorDefinition("FL", TpmsSensorConfiguration.defaultSensorId("FL"), TpmsReading(34, 32)),
        SensorDefinition("FR", TpmsSensorConfiguration.defaultSensorId("FR"), TpmsReading(35, 33)),
        SensorDefinition("RL", TpmsSensorConfiguration.defaultSensorId("RL"), TpmsReading(33, 29)),
        SensorDefinition("RR", TpmsSensorConfiguration.defaultSensorId("RR"), TpmsReading(34, 30)),
        SensorDefinition("SP", TpmsSensorConfiguration.defaultSensorId("SP"), TpmsReading(36, 18))
    )

    val currentReadings = sensorDefinitions.associate { it.position to it.initialReading }.toMutableMap()

    private val externallyUpdatedSignals = mutableSetOf<String>()
    private val random = Random.Default
    private var tpmsAdvertisementJob: Job? = null
    private var startupNotification = false
    private var activeEncoders: Map<String, TpmsManufacturerEncoder> = emptyMap()
    private var gattProfile: TpmsGattProfileInstance? = null
    private var protocolGattProfiles: Map<String, List<TpmsGattProfileInstance>> = emptyMap()

    val additionalGattServices: List<BluetoothGattService>
        get() = protocolGattProfiles.values.flatten().map { it.service }.distinctBy { it.uuid }

    val enabled: Boolean
        get() = prefs.getBoolean(TpmsManufacturerEncoders.PREF_ENABLED, DEFAULT_ENABLED)

    val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            if (startupNotification) {
                appendLog(getString(R.string.ble_tpms_service_started), LogLevel.INFO)
                startupNotification = false
            }
        }

        override fun onStartFailure(errorCode: Int) {
            appendLog(
                getString(R.string.ble_tpms_service_start_failed, advertiseErrorToString(errorCode)),
                LogLevel.ERROR
            )
        }
    }

    override fun getSettings(): AdvertiseSettings = AdvertiseSettings.Builder()
        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
        .setConnectable(false)
        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
        .build()

    override fun prepareStart(): Boolean = true

    override fun stop() {
        tpmsAdvertisementJob?.cancel()
        tpmsAdvertisementJob = null
        gattProfile = null
        protocolGattProfiles = emptyMap()
        activeEncoders = emptyMap()
    }

    override fun start(): BluetoothGattService? {
        stop()
        if (!enabled) return null
        updateSensorConfiguration()
        val selectedEncoders = resolveEncoders()
        if (selectedEncoders.isEmpty()) {
            appendLog(R.string.ble_tpms_start_fail_encoder, LogLevel.ERROR)
            return null
        }

        activeEncoders = selectedEncoders
        val activeDefinitions = sensorDefinitions.filter { selectedEncoders.containsKey(it.position) }
        val firstDefinition = activeDefinitions.first()
        gattProfile = TpmsGattProfile.create(currentReadings.getValue(firstDefinition.position))
        protocolGattProfiles = createProtocolGattProfiles(activeDefinitions, selectedEncoders)

        tpmsAdvertisementJob = scope.launch(Dispatchers.IO) {
            var index = 0
            while (isActive) {
                delay(DEFAULT_ADVERTISEMENT_INTERVAL_MS)
                if (!isActive) break
                val definition = activeDefinitions[index]
                try {
                    val reading = nextReading(definition)
                    val encoder = selectedEncoders.getValue(definition.position)
                    logDebug("Advertising TPMS sensor ${definition.position} with encoder ${encoder.id}: $reading")
                    onAdvertisingDataChanged(
                        TpmsSensorAdvertisement(
                            definition.position,
                            encoder.encode(reading, definition.sensorId.copyOf())
                        )
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    appendLog(getString(R.string.ble_tpms_rotation_failed, e.message), LogLevel.ERROR)
                }
                index = (index + 1) % activeDefinitions.size
            }
        }

        startupNotification = true
        return gattProfile?.service
    }

    fun updateSensorConfiguration() {
        sensorDefinitions.forEach { definition ->
            val newId = TpmsSensorConfiguration.sensorId(prefs, definition.position, definition.sensorId)
            if (!newId.contentEquals(definition.sensorId)) {
                logDebug(
                    "Updating sensor ID for ${definition.position} from " +
                        "${TpmsSensorConfiguration.formatId(definition.sensorId)} to " +
                        TpmsSensorConfiguration.formatId(newId)
                )
                definition.sensorId.fill(0)
                System.arraycopy(newId, 0, definition.sensorId, 0, minOf(newId.size, definition.sensorId.size))
            }
        }
    }

    private fun resolveEncoders(): Map<String, TpmsManufacturerEncoder> {
        val selected = LinkedHashMap<String, TpmsManufacturerEncoder>()
        val defaultId = prefs.getString(TpmsManufacturerEncoders.PREF_TYPE, DEFAULT_MANUFACTURER)
        sensorDefinitions.forEach { definition ->
            val key = TpmsManufacturerEncoders.preferenceKeyForPosition(definition.position)
            val id = if (prefs.contains(key)) prefs.getString(key, null) else defaultId
            if (id.isNullOrBlank()) return@forEach
            val encoder = TpmsManufacturerEncoders.find(id)
            if (encoder == null) {
                appendLog("Unsupported TPMS protocol for ${definition.position}: $id", LogLevel.ERROR)
            } else {
                selected[definition.position] = encoder
            }
        }
        return selected
    }

    fun getGattService(): BluetoothGattService? = gattProfile?.service

    @Synchronized
    fun getInitialSensor(): TpmsSensorAdvertisement? {
        if (!enabled) return null
        val encoders = activeEncoders.ifEmpty { resolveEncoders() }
        val definition = sensorDefinitions.firstOrNull { encoders.containsKey(it.position) } ?: return null
        val encoder = encoders.getValue(definition.position)
        return TpmsSensorAdvertisement(
            definition.position,
            encoder.encode(currentReadings.getValue(definition.position), definition.sensorId.copyOf())
        )
    }

    private fun createProtocolGattProfiles(
        definitions: List<SensorDefinition>,
        encoders: Map<String, TpmsManufacturerEncoder>
    ): Map<String, List<TpmsGattProfileInstance>> {
        val services = LinkedHashMap<java.util.UUID, Pair<String, TpmsGattProfileInstance>>()
        gattProfile?.let { services[it.service.uuid] = "__generic__" to it }
        val result = LinkedHashMap<String, List<TpmsGattProfileInstance>>()
        definitions.forEach { definition ->
            val encoder = encoders.getValue(definition.position)
            val profiles = encoder.createGattProfiles(
                definition.position,
                currentReadings.getValue(definition.position)
            ).mapNotNull { profile ->
                val previous = services[profile.service.uuid]
                when {
                    previous == null -> {
                        services[profile.service.uuid] = encoder.id to profile
                        profile
                    }
                    previous.first == encoder.id -> previous.second
                    else -> {
                        appendLog(
                            "Ignoring duplicate TPMS GATT service UUID ${profile.service.uuid} from ${encoder.id}",
                            LogLevel.WARNING
                        )
                        null
                    }
                }
            }
            if (profiles.isNotEmpty()) result[definition.position] = profiles
        }
        return result
    }

    @Synchronized
    fun updateTmpsSensor(signalPath: String, value: Double) {
        if (!value.isFinite()) return
        val path = signalPath.lowercase()
        val definition = sensorDefinitions.firstOrNull { path.startsWith("${it.position.lowercase()}_") } ?: return
        val position = definition.position
        val field = path.removePrefix("${position.lowercase()}_")
        val current = currentReadings.getValue(position)
        currentReadings[position] = when (field) {
            "pressure_psi", "pressure" -> {
                externallyUpdatedSignals.add("$position:pressure")
                current.copy(
                    pressurePsi = value.toInt().coerceIn(0, 255),
                    pressureCentiPsi = (value.coerceIn(0.0, 217.0) * 100.0).roundToInt()
                )
            }
            "temperature_c", "temperature" -> {
                externallyUpdatedSignals.add("$position:temperature")
                current.copy(
                    temperatureC = value.toInt().coerceIn(-110, 145),
                    temperatureCentiC = (value.coerceIn(-40.0, 125.0) * 100.0).roundToInt()
                )
            }
            "battery_percent", "battery" -> {
                externallyUpdatedSignals.add("$position:battery")
                current.copy(batteryPercent = value.toInt().coerceIn(0, 100))
            }
            "status_flags" -> {
                externallyUpdatedSignals.add("$position:flags")
                current.copy(statusFlags = value.toInt().coerceIn(0, 255))
            }
            "br_status_flags" -> {
                externallyUpdatedSignals.add("$position:br_flags")
                current.copy(brStatusFlags = value.toInt().coerceIn(0, 255))
            }
            "battery_voltage_tenths" -> {
                externallyUpdatedSignals.add("$position:battery_voltage")
                current.copy(batteryVoltageTenths = value.toInt().coerceIn(0, 255))
            }
            "br_checksum" -> {
                externallyUpdatedSignals.add("$position:br_checksum")
                current.copy(brChecksum = value.toInt().coerceIn(0, 0xFFFF))
            }
            else -> return
        }
        val updated = currentReadings.getValue(position)
        updateCharacteristics(updated)
        updateProtocolCharacteristics(position, updated)
    }

    @Synchronized
    private fun updateCharacteristics(reading: TpmsReading) {
        gattProfile?.update(reading)
    }

    @Synchronized
    private fun updateProtocolCharacteristics(position: String, reading: TpmsReading) {
        protocolGattProfiles[position]?.forEach { it.update(reading) }
    }

    @Synchronized
    private fun nextReading(definition: SensorDefinition): TpmsReading {
        val position = definition.position
        val current = currentReadings.getValue(position)
        val baseline = definition.initialReading
        val pressure = if ("$position:pressure" in externallyUpdatedSignals) current.pressurePsi else {
            (current.pressurePsi + random.nextInt(-1, 2))
                .coerceIn((baseline.pressurePsi - 2).coerceAtLeast(0), (baseline.pressurePsi + 2).coerceAtMost(255))
        }
        val temperature = if ("$position:temperature" in externallyUpdatedSignals) current.temperatureC else {
            (current.temperatureC + if (random.nextInt(0, 4) == 0) random.nextInt(-1, 2) else 0)
                .coerceIn((baseline.temperatureC - 4).coerceAtLeast(-110), (baseline.temperatureC + 4).coerceAtMost(145))
        }
        val next = current.copy(pressurePsi = pressure, temperatureC = temperature)
        currentReadings[position] = next
        updateCharacteristics(next)
        updateProtocolCharacteristics(position, next)
        return next
    }

    @Synchronized
    fun getSensors(): List<TpmsSensorAdvertisement> {
        if (!enabled) return emptyList()
        val encoders = activeEncoders.ifEmpty { resolveEncoders() }
        return sensorDefinitions.mapNotNull { definition ->
            val encoder = encoders[definition.position] ?: return@mapNotNull null
            TpmsSensorAdvertisement(
                definition.position,
                encoder.encode(currentReadings.getValue(definition.position), definition.sensorId.copyOf())
            )
        }
    }
}
