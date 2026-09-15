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

abstract class EmuInterface {

    protected var loopbackInput: InputStream? = null
    protected var loopbackOutput: OutputStream? = null
    protected var loopbackSocket: LocalSocket? = null
    
    public fun send(buffer: ByteArray, size: Int) {
        loopbackOutput?.write(buffer, 0, size)
        loopbackOutput?.flush()
    }

    public fun recv(buffer: ByteArray): Int {
        return loopbackInput?.read(buffer) ?: -1
    }

}

/**
 * Driven by the need of hotpluging and unplugging interfaces, this class orchestrates the bridges and the emulator.
 */
class BridgeOrchestrator(
    private val activity: MainActivity,
    private val basePort: Int = 35000
): EmuInterface() {

    protected val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    public val bleBridge = BLEBridge(this, scope, activity)
    private var bleBridgeJob: Job? = null
    public val ntBridge = NetworkBridge(this, scope, activity, basePort)
    private var ntBridgeJob: Job? = null
    public val btBridge = BluetoothBridge(this, scope, activity)
    private var btBridgeJob: Job? = null
    private val prefs =
        activity.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    private var started = false

    suspend fun start() {
        emuStart()

        activity.clearSocketFiles()
        started = true
        updateIFace()
    }

    /**
     * Without need to start or stop, setup interfaces used by emulator
     */
    suspend fun updateIFace() {
        val bridges = listOf(
            Triple(bleBridge, "com_ble_enabled", { job: Job? -> bleBridgeJob = job }),
            Triple(ntBridge, "com_nt_enabled", { job: Job? -> ntBridgeJob = job }),
            Triple(btBridge, "com_bt_enabled", { job: Job? -> btBridgeJob = job })
        )

        for ((bridge, pref, setJob) in bridges) {
            val enabled = prefs.getBoolean(pref, true)
            val job = when (bridge) {
                bleBridge -> bleBridgeJob
                ntBridge -> ntBridgeJob
                btBridge -> btBridgeJob
                else -> null
            }

            if (enabled && job == null) {
                bridge.start()

                setJob(scope.launch {
                    while (isActive) {
                        bridge.accept()
                    }
                })
            } else if (!enabled && job != null) {
                bridge.stop()
                job.cancel()
                setJob(null)
            } else if (enabled && job != null) {
                // Already running, do nothing
            } else if (!enabled && job == null) {
                // Already stopped, do nothing
            }
        }
    }

    fun stop() {
        scope.coroutineContext.cancelChildren()
        emuStop()
        started = false
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
        loopbackSocket = LocalSocket()
        loopbackSocket?.connect(
            LocalSocketAddress(location, LocalSocketAddress.Namespace.FILESYSTEM)
        )
        appendLog(getString(R.string.log_network_loopback_connected), LogLevel.DEBUG)

        loopbackInput = loopbackSocket?.inputStream
        loopbackOutput = loopbackSocket?.outputStream
    }

    protected fun emuStop() {
        loopbackInput?.close()
        loopbackOutput?.close()
        loopbackSocket?.close()
    }

}