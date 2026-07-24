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

open class Bridge(
    private val activity: MainActivity,
) {

    protected val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var loopbackInput: InputStream? = null
    private var loopbackOutput: OutputStream? = null
    private var loopbackSocket: LocalSocket? = null

    protected fun appendLog(text: String, level: LogLevel = LogLevel.DEBUG) {
        activity.appendLog(text, level)
    }

    protected fun getString(resId: Int, vararg formatArgs: Any?): String {
        return activity.getString(resId, *formatArgs.map { it ?: "" }.toTypedArray())
    }

    open fun start() {
        scope.launch {
            activity.clearSocketFiles()
            while (isActive) {
                startInternal()
            }
        }
    }

    protected open suspend fun startInternal() {

    }

    open fun stop() {
        scope.coroutineContext.cancelChildren()
        emuStop()
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

    protected fun emuSend(buffer: ByteArray, size: Int) {
        loopbackOutput?.write(buffer, 0, size)
        loopbackOutput?.flush()
    }

    protected fun emuRecv(buffer: ByteArray): Int {
        return loopbackInput?.read(buffer) ?: -1
    }

}