package com.github.autodiag2.elm327emu.sim

import android.net.LocalSocket
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

class EmuManaged: EmuInterface(LOG_TAG = "EmuManaged") {
    var socket: LocalSocket? = null
    var input: InputStream? = null
    var output: OutputStream? = null

    override fun send(
        buffer: ByteArray,
        size: Int,
        timeoutMs: Long
    ) {
        val stream = output ?: return

        logDebug("Writting ${buffer}")
        if (stream is PipedOutputStream) {
            runWithTimeout(timeoutMs) {
                stream.write(buffer, 0, size)
                stream.flush()
            }
        } else {
            try {
                stream.write(buffer, 0, size)
                stream.flush()
            } catch (e: SocketTimeoutException) {
                throw e
            }
        }
    }

    override fun recv(
        buffer: ByteArray,
        timeoutMs: Long
    ): Int {
        val stream = input ?: return -1

        logDebug("Recv ${buffer}")
        if (stream is PipedInputStream) {
            return runWithTimeout(timeoutMs) {
                stream.read(buffer)
            }
        }

        return try {
            stream.read(buffer)
        } catch (e: SocketTimeoutException) {
            throw e
        }
    }

    private fun <T> runWithTimeout(
        timeoutMs: Long,
        operation: () -> T
    ): T {
        val result = arrayOfNulls<Any?>(1)
        val error = arrayOfNulls<Throwable>(1)

        val worker = thread(
            start = true,
            isDaemon = true
        ) {
            try {
                result[0] = operation()
            } catch (e: Throwable) {
                error[0] = e
            }
        }

        worker.join(timeoutMs)

        if (worker.isAlive) {
            throw SocketTimeoutException(
                "I/O operation timed out after ${timeoutMs}ms"
            )
        }

        error[0]?.let {
            throw it
        }

        @Suppress("UNCHECKED_CAST")
        return result[0] as T
    }
}