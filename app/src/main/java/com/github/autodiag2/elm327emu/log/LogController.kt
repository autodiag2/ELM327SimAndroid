package com.github.autodiag2.elm327emu.log

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.github.autodiag2.elm327emu.log.LogRepository.AppendResult
import com.github.autodiag2.elm327emu.log.LogEntry
import java.io.File

class LogController(private val context: Context) {

    val model = LogRepository(context)
    var view: LogView? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainScope = CoroutineScope(Dispatchers.Main)
    var filename = "elm327emu_log.txt"
    var searchText = ""

    fun append(text: String, level: LogLevel = LogLevel.DEBUG, data: ByteArray? = null, size_used: Int = data?.size ?: 0, serial: ByteArray? = null, serial_size: Int = serial?.size ?: 0, type: LogEntryType = LogEntryType.NONE) {
        scope.launch {

            val result: AppendResult? = model.append(text, level, data, size_used, serial, serial_size, type)
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
            val result = model.logData(type, data, length)
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

    fun save() {
        val view = this.view
        if ( view == null ) {
            return
        }
        val text = model.snapshotUnsafe().joinToString("\n") { it.text }
        view.modelText = text
        view.save(filename)
    }

    fun download() {
        val view = this.view
        if ( view == null ) {
            return
        }
        scope.launch {
            val file = File(context.getExternalFilesDir(null), filename)
            file.writeText(model.snapshotUnsafe().joinToString("\n") { it.text })
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