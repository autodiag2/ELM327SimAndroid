package com.github.autodiag2.elm327emu.sim

import java.io.InputStream
import java.io.OutputStream
import android.net.LocalSocket

abstract class EmuInterface {

    protected var input: InputStream? = null
    protected var output: OutputStream? = null
    protected var socket: LocalSocket? = null
    
    public fun send(buffer: ByteArray, size: Int) {
        output?.write(buffer, 0, size)
        output?.flush()
    }

    public fun recv(buffer: ByteArray): Int {
        return input?.read(buffer) ?: -1
    }

    private var inputBackup: InputStream? = null
    private var outputBackup: OutputStream? = null

    fun emuHookStreams(hookInput: InputStream, hookOutput: OutputStream) {
        if ( inputBackup == null ) {
            inputBackup = input
        }
        if ( outputBackup == null ) {
            outputBackup = output
        }
        input = hookInput
        output = hookOutput
    }

    fun emuUnHookStreams() {
        if ( inputBackup != null ) {
            input = inputBackup
        }
        if ( outputBackup != null ) {
            output = outputBackup
        }
    }

}