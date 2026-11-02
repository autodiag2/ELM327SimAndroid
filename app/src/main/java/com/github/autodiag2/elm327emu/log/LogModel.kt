package com.github.autodiag2.elm327emu.log

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.github.autodiag2.elm327emu.SimSignal


enum class LogLevel(val value: Int) {
    NONE(0),
    ERROR(1),
    WARNING(2),
    INFO(3),
    DEBUG(4)
}

val LogLevel_DEFAULT = LogLevel.INFO

enum class LogEntryType {
    RECV, SENT, NONE, SEPARATOR
}

data class LogEntry(
    val id: Long,
    val text: String,
    val level: LogLevel,
    val data: ByteArray,
    var serial: ByteArray,
    var match: Boolean = false,
    var count: Int = 0,
    val type: LogEntryType = LogEntryType.NONE,
    var pendingSignal: Pair<SimSignal, Double>? = null,
    val ts: Long = System.currentTimeMillis()
) {
    companion object {
        fun dataLogFormat(
            data: ByteArray,
            length: Int = data.size,
            charsPerLine: Int = 10
        ): String {
            val sb = StringBuilder()
            val indent = 1
            val asciiSeparator = 2
            val usable = charsPerLine - indent - asciiSeparator
            val bytesPerLine = (usable / 4).coerceIn(4, 64)
            for (i in 0 until length step bytesPerLine) {
                sb.append(" ")
                val lineEnd = minOf(i + bytesPerLine, length)
                for (j in i until i + bytesPerLine) {
                    if (j < lineEnd) {
                        sb.append(String.format("%02X ", data[j]))
                    } else {
                        sb.append("   ")
                    }
                }
                sb.append("  ")
                formatDataToString(sb, data, length, i, lineEnd)
                sb.append("\n")
            }
            return sb.toString()
        }

        fun formatDataToString(
            sb: StringBuilder,
            data: ByteArray,
            length: Int = data.size,
            start_i: Int = 0,
            lineEnd: Int = length
        ) {
            for (j in start_i until lineEnd) {
                val b = data[j].toInt() and 0xFF
                val c = if (b in 32..126) b.toChar() else '.'
                sb.append(c)
            }
        }
    }
}

class LogRepository(private val context: Context) {
    public val buffer = ArrayList<LogEntry>()
    private val mutex = Mutex()
    private var counter = 0L
    val prefs by lazy { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }

    companion object {
        const val PREF_LOG_GROUP = "log_exchange_dup_search"
        const val PREF_LOG_GROUP_DEFAULT = false
    }

    data class AppendResult(
        val entry: LogEntry,
        val inserted: Boolean,
        val deleted: LogEntry?,
        val modified: LogEntry?
    )

    suspend fun append(
        text: String,
        level: LogLevel = LogLevel.DEBUG,
        data: ByteArray? = null,
        size_used: Int = data?.size ?: 0,
        serial: ByteArray? = null,
        serial_size: Int = serial?.size ?: 0,
        type: LogEntryType = LogEntryType.NONE,
        pendingSignal: Pair<SimSignal, Double>? = null
    ): AppendResult? {
        val currentLevel = prefs.getInt("log_level", LogLevel_DEFAULT.ordinal)
        if (currentLevel < level.ordinal) return null
        return mutex.withLock {
            var entry: LogEntry? = null
            var deleted: LogEntry? = null
            var modified: LogEntry? = null
            if (prefs.getBoolean(PREF_LOG_GROUP, PREF_LOG_GROUP_DEFAULT) &&
                type == LogEntryType.SENT &&
                buffer.size >= 3
            ) {
                var exchanges = 0
                var currentRecv: LogEntry? = null
                var currentRecvIdx = -1
                for (i in buffer.size - 1 downTo 1) {
                    if (buffer[i].type == LogEntryType.RECV) {
                        currentRecv = buffer[i]
                        currentRecvIdx = i
                        break
                    }
                }
                if (currentRecv != null) {
                    for (i in currentRecvIdx - 1 downTo 0) {
                        if (buffer[i].type != LogEntryType.RECV) {
                            continue
                        }
                        var sent_idx = -1
                        for (idx in (i + 1) until currentRecvIdx) {
                            if (buffer[idx].type == LogEntryType.SENT) {
                                sent_idx = idx
                                break
                            } else if (buffer[idx].type == LogEntryType.RECV) {
                                break
                            }
                        }
                        if (sent_idx == -1) {
                            continue
                        }
                        exchanges++
                        if (exchanges > prefs.getInt("log_exchange_dup_search_depth", 10)) {
                            break
                        }
                        val previousRecv = buffer[i]
                        val previousSent = buffer[sent_idx]
                        if ((text != "" && previousSent.text != "" &&
                                previousRecv.text != "" && currentRecv.text != "" &&
                                previousRecv.text == currentRecv.text &&
                                previousSent.text == text) ||
                            previousSent.serial.contentEquals(serial?.copyOf(serial_size) ?: ByteArray(0))
                        ) {
                            deleted = buffer.removeAt(currentRecvIdx)
                            previousRecv.count++
                            modified = previousRecv
                            entry = previousSent
                            if (pendingSignal != null) {
                                entry.pendingSignal = pendingSignal
                            }
                            break
                        }
                    }
                }
            }
            val inserted = entry == null
            if (entry == null) {
                entry = LogEntry(
                    id = counter++,
                    text = text,
                    level = level,
                    data = data?.copyOf(size_used) ?: ByteArray(0),
                    serial = serial?.copyOf(serial_size) ?: ByteArray(0),
                    type = type,
                    pendingSignal = pendingSignal
                )
                buffer.add(entry)
            }
            entry.count++
            AppendResult(entry, inserted, deleted, modified)
        }
    }

    suspend fun clear() {
        mutex.withLock {
            buffer.clear()
        }
    }

    suspend fun snapshot(): List<LogEntry> {
        return mutex.withLock {
            buffer.toList()
        }
    }

    fun snapshotUnsafe(): List<LogEntry> {
        return if (mutex.tryLock()) {
            try {
                buffer.toList()
            } finally {
                mutex.unlock()
            }
        } else {
            emptyList()
        }
    }

    private fun decodeHexAscii(data: ByteArray, length: Int): ByteArray? {
        val out = ArrayList<Byte>()
        var hi = -1
        fun hex(c: Int): Int = when (c) {
            in '0'.code..'9'.code -> c - '0'.code
            in 'A'.code..'F'.code -> c - 'A'.code + 10
            in 'a'.code..'f'.code -> c - 'a'.code + 10
            else -> -1
        }
        for (i in 0 until length) {
            val b = data[i].toInt() and 0xff
            when (b) {
                '\r'.code, '\n'.code, ' '.code, '>'.code -> continue
            }
            val v = hex(b)
            if (v < 0) {
                return null
            }
            if (hi < 0) {
                hi = v
            } else {
                out += ((hi shl 4) or v).toByte()
                hi = -1
            }
        }
        if (hi >= 0) {
            return null
        }
        return out.toByteArray()
    }

    suspend fun logData(
        type: LogEntryType,
        data: ByteArray,
        length: Int = data.size,
        pendingSignal: Pair<SimSignal, Double>? = null
    ): AppendResult? {
        val binary = decodeHexAscii(data, length) ?: ByteArray(0)
        return append(
            "",
            LogLevel.INFO,
            binary,
            binary.size,
            data,
            length,
            type,
            pendingSignal
        )
    }
}
