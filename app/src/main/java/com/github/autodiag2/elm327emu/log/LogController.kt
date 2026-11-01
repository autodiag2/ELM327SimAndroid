package com.github.autodiag2.elm327emu.log

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.github.autodiag2.elm327emu.log.LogRepository.AppendResult
import com.github.autodiag2.elm327emu.log.LogEntry
import java.io.File
import com.github.autodiag2.elm327emu.SimSignal
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.util.Log

class LogController(private val context: Context) {

    val model = LogRepository(context)
    var view: LogView? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainScope = CoroutineScope(Dispatchers.Main)
    var filename = "elm327emu_log.txt"
    var searchText = ""
    private val pendingSignalMutex = Mutex()
    var pendingSignal: Pair<SimSignal, Double>? = null
        set(value) {
            field = value
        }

    suspend fun setPendingSignal(signal: Pair<SimSignal, Double>?) {
        pendingSignalMutex.withLock {
            pendingSignal = signal
        }
    }

    fun append(text: Any, level: LogLevel = LogLevel.DEBUG, data: ByteArray? = null, size_used: Int = data?.size ?: 0, serial: ByteArray? = null, serial_size: Int = serial?.size ?: 0, type: LogEntryType = LogEntryType.NONE) {
        scope.launch {
            var decodedText = ""
            if ( text is Int ) {
                decodedText = context.getString(text)
            } else if ( text is String ) {
                decodedText = text
            }
            val pendingSignal = if (type == LogEntryType.SENT) {
                pendingSignalMutex.withLock {
                    this@LogController.pendingSignal.also {
                        this@LogController.pendingSignal = null
                    }
                }
            } else {
                null
            }
            val result: AppendResult? = model.append(decodedText, level, data, size_used, serial, serial_size, type, pendingSignal)
            val view = this@LogController.view
            if ( view == null ) {
                return@launch
            }
            mainScope.launch {
                refresh(result)
                // ---- ONLY auto-scroll if allowed ----
                if (view.stickToBottom && !view.userTouching) {
                    view.scrollToBottomSafe()
                }
            }
        }
    }

    fun logData(
        type: LogEntryType,
        data: ByteArray,
        length: Int = data.size
    ) {
        scope.launch {
            val pendingSignal = if (type == LogEntryType.SENT) {
                pendingSignalMutex.withLock {
                    this@LogController.pendingSignal.also {
                        this@LogController.pendingSignal = null
                    }
                }
            } else {
                null
            }
            val result = model.logData(type, data, length, pendingSignal)
            refresh(result)
        }
    }

    fun clear() {
        scope.launch {
            model.clear()
            mainScope.launch {
                refresh()
            }
        }
    }

    fun logAsText(): String {
        return model.snapshotUnsafe().joinToString("\n") {
            if (it.text.isNotEmpty()) it.text
            else LogEntry.dataLogFormat(it.serial, it.serial.size, view?.logAdapter?.charsPerLine ?: 80)
        }
    }

    fun save() {
        val view = this.view
        if ( view == null ) {
            return
        }
        view.modelText = logAsText()
        view.save(filename)
    }

    fun download() {
        val view = this.view
        if ( view == null ) {
            return
        }
        scope.launch {
            val file = File(context.getExternalFilesDir(null), filename)
            file.writeText(logAsText())
            view.onDownload(filename)
        }
    }

    fun refresh(result: AppendResult? = null) {
        val view = this.view
        if ( view == null ) {
            return
        }
        scope.launch {
            val entries = model.search(searchText)
            view.refresh(result, entries)
        }
    }

    fun onSearchChanged(text: String) {
        this.searchText = text
        refresh()
    }

    fun attachView(view: LogView) {
        this.view = view
        view.onSearchChanged = ::onSearchChanged
        view.onWidthChanged = {
            refresh()
        }
        refresh()
    }

    fun detachView() {
        this.view = null
    }

}