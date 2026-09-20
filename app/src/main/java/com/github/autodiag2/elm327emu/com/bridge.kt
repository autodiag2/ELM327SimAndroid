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
import com.github.autodiag2.elm327emu.sim.EmuInterface

open class Bridge(
    protected val emu: EmuInterface,
    protected val scope: CoroutineScope,
    protected val activity: MainActivity,
    private val LOG_TAG: String
) {
    protected fun appendLog(text: String, level: LogLevel = LogLevel.DEBUG) {
        activity.appendLog(LOG_TAG + ": " + text, level)
    }

    protected fun getString(resId: Int, vararg formatArgs: Any?): String {
        return activity.getString(resId, *formatArgs.map { it ?: "" }.toTypedArray())
    }

    open suspend fun start() {
        
    }

    open fun stop() {
        
    }

    open suspend fun accept() {
    
    }

}