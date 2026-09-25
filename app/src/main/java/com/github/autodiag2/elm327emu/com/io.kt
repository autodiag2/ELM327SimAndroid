package com.github.autodiag2.elm327emu.com

import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

abstract class IO(
    var input: InputStream? = null, 
    var output: OutputStream? = null
) {
    protected val ioMutex = Mutex()

    fun set(input: InputStream, output: OutputStream) {
        this.input = input
        this.output = output
    }

    suspend fun transact(
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
}
