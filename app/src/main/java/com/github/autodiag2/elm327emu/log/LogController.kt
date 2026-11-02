package com.github.autodiag2.elm327emu.log

import android.content.Context
import com.github.autodiag2.elm327emu.SimSignal
import com.github.autodiag2.elm327emu.log.LogRepository.AppendResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class LogController(private val context: Context) {

    val model = LogRepository(context)
    var view: LogView? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainScope = CoroutineScope(Dispatchers.Main)
    var filename = "elm327emu_log.txt"
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

    fun append(
        text: Any,
        level: LogLevel = LogLevel.DEBUG,
        data: ByteArray? = null,
        size_used: Int = data?.size ?: 0,
        serial: ByteArray? = null,
        serial_size: Int = serial?.size ?: 0,
        type: LogEntryType = LogEntryType.NONE
    ) {
        scope.launch {
            val decodedText = when (text) {
                is Int -> context.getString(text)
                is String -> text
                else -> ""
            }
            val signal = if (type == LogEntryType.SENT) {
                pendingSignalMutex.withLock {
                    this@LogController.pendingSignal.also {
                        this@LogController.pendingSignal = null
                    }
                }
            } else {
                null
            }
            val result: AppendResult? = model.append(
                decodedText,
                level,
                data,
                size_used,
                serial,
                serial_size,
                type,
                signal
            )
            if (result == null || this@LogController.view == null) {
                return@launch
            }
            refresh(result)
        }
    }

    fun logData(
        type: LogEntryType,
        data: ByteArray,
        length: Int = data.size
    ) {
        scope.launch {
            val signal = if (type == LogEntryType.SENT) {
                pendingSignalMutex.withLock {
                    this@LogController.pendingSignal.also {
                        this@LogController.pendingSignal = null
                    }
                }
            } else {
                null
            }
            val result = model.logData(type, data, length, signal)
            if (result != null) {
                refresh(result)
            }
        }
    }

    fun clear() {
        scope.launch {
            model.clear()
            refresh()
        }
    }

    fun logAsText(): String {
        return model.snapshotUnsafe().joinToString("\n") {
            if (it.text.isNotEmpty()) it.text
            else LogEntry.dataLogFormat(
                it.serial,
                it.serial.size,
                view?.logAdapter?.charsPerLine ?: 80
            )
        }
    }

    fun save() {
        val currentView = view ?: return
        currentView.modelText = logAsText()
        currentView.save(filename)
    }

    fun download() {
        val currentView = view ?: return
        scope.launch {
            val file = File(context.getExternalFilesDir(null), filename)
            file.writeText(logAsText())
            currentView.onDownload(filename)
        }
    }

    fun refresh(result: AppendResult? = null) {
        val currentView = view ?: return
        scope.launch {
            val entries = if (result == null) model.snapshot() else null
            mainScope.launch {
                if (this@LogController.view !== currentView) {
                    return@launch
                }
                currentView.refresh(result, entries)
            }
        }
    }

    fun onSearchChanged(text: String) {
        val currentView = view ?: return
        currentView.search(text)
    }

    fun attachView(view: LogView) {
        this.view = view
        view.onSearchChanged = ::onSearchChanged
        view.onWidthChanged = {
            if (this.view === view) {
                view.refreshSearch()
            }
        }
        refresh()
    }

    fun detachView() {
        val currentView = view ?: return
        currentView.cancelSearch()
        currentView.onSearchChanged = null
        currentView.onWidthChanged = null
        view = null
    }
}
