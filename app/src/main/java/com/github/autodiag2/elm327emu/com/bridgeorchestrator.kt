package com.github.autodiag2.elm327emu.com

import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.github.autodiag2.elm327emu.LogLevel
import com.github.autodiag2.elm327emu.MainActivity
import com.github.autodiag2.elm327emu.R
import com.github.autodiag2.elm327emu.libautodiag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.SharedPreferences
import com.github.autodiag2.elm327emu.sim.EmuInterface
import android.util.Log
import com.github.autodiag2.elm327emu.BuildConfig
import kotlinx.coroutines.*

/**
 * Driven by the need of hotpluging and unplugging interfaces, this class orchestrates the bridges and the emulator.
 */
class BridgeOrchestrator(
    private val activity: MainActivity,
    private val basePort: Int = 35000,
    private val clientConnectionListener: ClientConnectionListener? = null
): EmuInterface(), Bridge.Listener {

    protected val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    public val bleBridge = BLEBridge(emu = this, scope = scope, activity = activity, listener = this)
    private var bleBridgeJob: Job? = null
    public val ntBridge = NetworkBridge(emu = this, scope = scope, activity = activity, basePort = basePort, listener = this)
    private var ntBridgeJob: Job? = null
    public val btBridge = BluetoothBridge(emu = this, scope = scope, activity = activity, listener = this)
    private var btBridgeJob: Job? = null
    private val prefs =
        activity.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    data class ConnectedClient(
        val clientIdentifier: String,
        val bridge: Bridge
    )

    interface ClientConnectionListener {
        fun onClientConnectUpdate(
            clients: List<ConnectedClient>
        )
    }
    private val connectedClients = mutableListOf<ConnectedClient>()
    private var started = false

    fun logDebug(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(
                "com.BridgeOrchestrator",
                message
            )
        }
    }

    suspend fun start() {
        activity.clearSocketFiles()
        emuStart()

        started = true
        setupBridges()
    }

    override fun onClientConnect(
        clientIdentifier: String,
        bridge: Bridge
    ) {
        connectedClients.removeAll {
            it.clientIdentifier == clientIdentifier
        }

        connectedClients.add(
            ConnectedClient(
                clientIdentifier,
                bridge
            )
        )

        clientConnectionListener?.onClientConnectUpdate(
            connectedClients.toList()
        )
    }

    override fun onClientDisconnect(
        clientIdentifier: String,
        bridge: Bridge
    ) {
        connectedClients.removeAll {
            it.clientIdentifier == clientIdentifier &&
                it.bridge === bridge
        }

        clientConnectionListener?.onClientConnectUpdate(
            connectedClients.toList()
        )
    }

    fun setupNetworkBridge() {
        scope.launch {
            setupBridge("com_nt_enabled", ntBridge)
        }
    }
    fun setupBleBridge() {
        scope.launch {
            setupBridge("com_ble_enabled", bleBridge)
        }
    }
    fun setupBluetoothBridge() {
        scope.launch {
            setupBridge("com_bt_enabled", btBridge)
        }
    }
    suspend fun setupBridge(pref: String, bridge: Bridge) {
        val enabled = prefs.getBoolean(pref, true)
        val job = when (bridge) {
            bleBridge -> bleBridgeJob
            ntBridge -> ntBridgeJob
            btBridge -> btBridgeJob
            else -> null
        }
        val setJob = { local_job: Job? -> 
            when(bridge) {
                bleBridge -> bleBridgeJob = local_job 
                ntBridge -> ntBridgeJob = local_job 
                btBridge -> btBridgeJob = local_job 
            }
        }
        if (enabled) {
            if ( job != null ) {
                bridge.stop()
                job.cancel()
                setJob(null)
            }
            bridge.start()

            setJob(scope.launch {

                logDebug("Accept job STARTED bridge=${bridge.javaClass.simpleName}")

                while (isActive) {

                    logDebug(
                        "Before accept bridge=${bridge.javaClass.simpleName} " +
                        "active=$isActive"
                    )

                    if (
                        (bridge is BLEBridge || bridge is BluetoothBridge) &&
                        !activity.btAdapter.isEnabled
                    ) {
                        logDebug("bluetooth not enabled, abort launch")
                        break
                    }

                    bridge.accept()

                    logDebug(
                        "After accept bridge=${bridge.javaClass.simpleName} " +
                        "active=$isActive " +
                        "cancelled=${coroutineContext.job.isCancelled}"
                    )
                }

                logDebug(
                    "Accept job exited bridge=${bridge.javaClass.simpleName} " +
                    "active=$isActive " +
                    "cancelled=${coroutineContext.job.isCancelled}"
                )
            })
        } else if (!enabled && job != null) {
            bridge.stop()
            job.cancel()
            setJob(null)
        } else if (!enabled && job == null) {
            // Already stopped, do nothing
        }
    }

    /**
     * Refresh interfaces with correct settings (cause a disconnection/reconnection of scantool)
     */
    fun setupBridges() {
        setupNetworkBridge()
        setupBleBridge()
        setupBluetoothBridge()
    }

    fun stop() {
        scope.coroutineContext.cancelChildren()
        emuStop()
        started = false
        connectedClients.clear()
        clientConnectionListener?.onClientConnectUpdate(
            connectedClients.toList()
        )
    }

    fun isStarted(): Boolean {
        return started
    }

    protected fun appendLog(text: String, level: LogLevel = LogLevel.DEBUG) {
        activity.appendLog(text, level)
    }

    protected fun getString(resId: Int, vararg formatArgs: Any?): String {
        return activity.getString(resId, *formatArgs.map { it ?: "" }.toTypedArray())
    }

    protected fun emuStart() {
        val filesDirPath = activity.filesDir.absolutePath
        val location = libautodiag.launchEmu(filesDirPath)
        appendLog(getString(R.string.log_network_native_sim_location, location),
            LogLevel.DEBUG
        )
        socket = LocalSocket()
        socket?.connect(
            LocalSocketAddress(location, LocalSocketAddress.Namespace.FILESYSTEM)
        )
        appendLog(getString(R.string.log_network_loopback_connected), LogLevel.DEBUG)

        input = socket?.inputStream
        output = socket?.outputStream
    }

    protected fun emuStop() {
        input?.close()
        output?.close()
        socket?.close()
    }

}