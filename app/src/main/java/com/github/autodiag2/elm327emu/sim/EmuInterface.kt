package com.github.autodiag2.elm327emu.sim

import kotlinx.coroutines.sync.Mutex
import android.util.Log
import com.github.autodiag2.elm327emu.BuildConfig
import kotlinx.coroutines.sync.withLock

abstract class EmuInterface(
    private val LOG_TAG: String = "sim.EmuInterface"
) {

    interface Provider {
        fun getEmu(): EmuInterface
        fun setEmu(emu: EmuInterface)
        fun resetEmu()
    }

    protected val ioMutex = Mutex()

    public fun logDebug(
        message: String
    ) {
        if (BuildConfig.DEBUG) {
            Log.d(
                LOG_TAG,
                message
            )
        }
    }

    open suspend fun transact(
        request: ByteArray,
        size: Int,
        response: ByteArray,
        timeoutMs: Long = 5000L
    ): Int {
        return ioMutex.withLock {
            send(request, size, timeoutMs = timeoutMs)
            recv(response, timeoutMs = timeoutMs)
        }
    }

    /**
     * Send to an emu interface (from the outside)
     */
    abstract fun send(
        buffer: ByteArray,
        size: Int,
        timeoutMs: Long = 5000L
    )

    /**
     * Receive to an emu interface (from the outside)
     */
    abstract fun recv(
        buffer: ByteArray,
        timeoutMs: Long = 5000L
    ): Int

}