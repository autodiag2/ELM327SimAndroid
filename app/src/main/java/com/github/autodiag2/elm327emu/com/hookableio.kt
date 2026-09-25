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

abstract class HookableIO(
    input: InputStream? = null,
    output: OutputStream? = null
): IO(input = input, output = output) {
    private var inputBackup: InputStream? = null
    private var outputBackup: OutputStream? = null

    fun close() {
        inputBackup = null
        outputBackup?.close() ?: output?.close()
        outputBackup = null
        input = null
        output = null
    }

    fun hookIO(
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

    fun unhookIO() {
        if (inputBackup != null) {
            input = inputBackup
        }

        if (outputBackup != null) {
            output = outputBackup
        }
    }
}