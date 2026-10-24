
package com.github.autodiag2.elm327emu.log

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    val ts: Long = System.currentTimeMillis()
) {
    companion object {
        fun dataLogFormat(
            data: ByteArray,
            length: Int = data.size,
            charsPerLine: Int = 10
        ): String {
            val sb = StringBuilder()
    
            // fixed layout parts
            val indent = 1
            val hexByteWidth = 3      // "FF "
            val asciiSeparator = 2    // "  "
    
            // reserve space for ASCII area (~1 char per byte)
            val usable = charsPerLine - indent - asciiSeparator
    
            // each byte takes ~4 chars in total (hex + space)
            val bytesPerLine = (usable / 4).coerceIn(4, 64)
    
            for (i in 0 until length step bytesPerLine) {
    
                sb.append(" ")
    
                val lineEnd = minOf(i + bytesPerLine, length)
    
                // HEX PART
                for (j in i until i + bytesPerLine) {
                    if (j < lineEnd) {
                        sb.append(String.format("%02X ", data[j]))
                    } else {
                        sb.append("   ")
                    }
                }
    
                sb.append("  ")
    
                // ASCII PART
                formatDataToString(sb, data, length, i, lineEnd)
    
                sb.append("\n")
            }
    
            return sb.toString()
        }
        fun formatDataToString(sb: StringBuilder, data: ByteArray, length: Int = data.size, start_i: Int = 0, lineEnd: Int = length) {
            for (j in start_i until lineEnd) {
                val b = data[j].toInt() and 0xFF
                val c = if (b in 32..126) b.toChar() else '.'
                sb.append(c)
            }
        }
    }
}

private fun parseHexString(text: String): ByteArray? {
    val hex = text.filter {
        !it.isWhitespace() && it != ':' && it != '-'
    }

    if (hex.length % 2 != 0) return null
    if (!hex.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }) return null

    return ByteArray(hex.length / 2) { i ->
        hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}

private fun containsSubArray(data: ByteArray, pattern: ByteArray): Boolean {
    if (pattern.isEmpty()) return true
    if (pattern.size > data.size) return false

    outer@ for (i in 0 .. data.size - pattern.size) {
        for (j in pattern.indices) {
            if (data[i + j] != pattern[j]) {
                continue@outer
            }
        }
        return true
    }

    return false
}

fun search_match(entry: LogEntry, search: String): Boolean {
    var text = entry.text
    if (text.contains(search, ignoreCase = true)) {
        return true
    }
    val sb = StringBuilder()
    LogEntry.formatDataToString(sb, entry.serial)
    text = sb.toString()
    if (text.contains(search, ignoreCase = true)) {
        return true
    }
    text = LogEntry.dataLogFormat(entry.serial)
    if ( text.contains(search, ignoreCase = true) ) {
        return true
    }

    val pattern = parseHexString(search) ?: return false

    return containsSubArray(entry.data, pattern)
}
class LogRepository(private val context: Context) {

    public val buffer = ArrayList<LogEntry>()
    private val mutex = Mutex()
    private var counter = 0L
    val prefs by lazy { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }

    suspend fun search(text: String): List<LogEntry> {
        return mutex.withLock {

            if (text.isBlank()) {
                return@withLock buffer.filter { it.type != LogEntryType.SEPARATOR }
            }

            val result = ArrayList<LogEntry>()
            val added = HashSet<Long>()

            val showSz = 2

            for (i in buffer.indices) {

                if (!search_match(buffer[i], text))
                    continue

                val first = maxOf(0, i - showSz)
                val last = minOf(buffer.lastIndex, i + showSz)

                if ( 0 < result.size ) {
                    if ( result[result.size-1].type != LogEntryType.SEPARATOR ) {

                        val previousMatchInWindow =
                            (first until i).any { search_match(buffer[it], text) }
        
                        if (!previousMatchInWindow) {
                            result += LogEntry(
                                id = -1,
                                text = "",
                                level = LogLevel.INFO,
                                data = ByteArray(0),
                                serial = ByteArray(0),
                                type = LogEntryType.SEPARATOR
                            )
                        }
                    }
                }

                for (j in first..last) {
                    if (added.add(buffer[j].id)) {
                        buffer[j].match = (i == j)
                        result += buffer[j]
                    }
                }

                if ( i < buffer.size - 1 ) {
                    val nextMatchInWindow =
                        (i + 1..last).any { search_match(buffer[it], text) }
    
                    if (!nextMatchInWindow) {
                        result += LogEntry(
                            id = -2,
                            text = "",
                            level = LogLevel.INFO,
                            data = ByteArray(0),
                            serial = ByteArray(0),
                            type = LogEntryType.SEPARATOR
                        )
                    }
                }
            }

            return result
        }
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
        type: LogEntryType = LogEntryType.NONE
    ): AppendResult? {
        val currentLevel = prefs.getInt("log_level", LogLevel_DEFAULT.ordinal)
        if (currentLevel < level.ordinal) return null
        
        return mutex.withLock {

            var entry: LogEntry? = null
            var deleted: LogEntry? = null
            var modified: LogEntry? = null
            if (prefs.getBoolean("log_exchange_dup_search", false) &&
                type == LogEntryType.SENT &&
                buffer.size >= 3
            ) {

                var exchanges = 0
                var currentRecv: LogEntry? = null
                var currentRecvIdx = -1
                for (i in buffer.size - 1 downTo 1) {
                    if ( buffer[i].type == LogEntryType.RECV ) {
                        currentRecv = buffer[i]
                        currentRecvIdx = i
                        break
                    }
                }
                if ( currentRecv != null ) {

                    for (i in currentRecvIdx - 1 downTo 0) {

                        if (buffer[i].type != LogEntryType.RECV) {
                            continue
                        }
                        var sent_idx = -1
                        for (idx in (i+1) until currentRecvIdx) {
                            if ( buffer[idx].type == LogEntryType.SENT ) {
                                sent_idx = idx
                                break
                            } else if ( buffer[idx].type == LogEntryType.RECV ) {
                                break
                            }
                        }
                        if ( sent_idx == -1 ) {
                            continue
                        }

                        exchanges++
                        if (exchanges > prefs.getInt("log_exchange_dup_search_depth", 10)) {
                            break
                        }

                        val previousRecv = buffer[i]
                        val previousSent = buffer[sent_idx]

                        if (previousRecv.text == currentRecv.text &&
                            previousSent.text == text
                        ) {
                            deleted = buffer.removeAt(currentRecvIdx)
                            previousRecv.count++
                            modified = previousRecv
                            entry = previousSent
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
                    type = type
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
        length: Int = data.size
    ): AppendResult? {
        val binary = decodeHexAscii(data, length)
            ?: ByteArray(0)
        
        return append(
            "",
            LogLevel.INFO,
            binary,
            binary.size,
            data,
            length,
            type
        )
    }

}