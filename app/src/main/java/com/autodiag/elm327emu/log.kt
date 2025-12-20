package com.autodiag.elm327emu

public enum class LogLevel(val value: Int) {
    ERROR(0),
    INFO(1),
    DEBUG(2)
}

public fun hexDump(buffer: ByteArray, size: Int): String {
    val colSize = 20
    val result = StringBuilder()
    var byteI = 0

    while (byteI < size) {
        val hexCollector = StringBuilder()
        val asciiCollector = StringBuilder()
        var col = 0

        while (col < colSize && byteI < size) {
            val b = buffer[byteI].toInt() and 0xFF

            if (col > 0) hexCollector.append(' ')
            hexCollector.append(String.format("%02x", b))

            asciiCollector.append(
                if (b in 0x20..0x7E) b.toChar() else '.'
            )

            col += 1
            byteI += 1
        }

        result.append(
            String.format(
                "%59s | %20s\n",
                hexCollector.toString(),
                asciiCollector.toString()
            )
        )
    }

    return result.toString()
}