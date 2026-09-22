package com.github.autodiag2.elm327emu.sim

import android.net.LocalSocket
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

abstract class EmuInterface {

    protected var input: InputStream? = null
    protected var output: OutputStream? = null
    protected var socket: LocalSocket? = null

    public fun send(
        buffer: ByteArray,
        size: Int,
        timeoutMs: Long = 5000L
    ) {
        val stream = output ?: return

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

    public fun recv(
        buffer: ByteArray,
        timeoutMs: Long = 5000L
    ): Int {
        val stream = input ?: return -1

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

    private var inputBackup: InputStream? = null
    private var outputBackup: OutputStream? = null

    fun emuHookStreams(
        hookInput: InputStream,
        hookOutput: OutputStream
    ) {
        if (inputBackup == null) {
            inputBackup = input
        }

        if (outputBackup == null) {
            outputBackup = output
        }

        input = hookInput
        output = hookOutput
    }

    fun emuUnHookStreams() {
        if (inputBackup != null) {
            input = inputBackup
        }

        if (outputBackup != null) {
            output = outputBackup
        }
    }
}