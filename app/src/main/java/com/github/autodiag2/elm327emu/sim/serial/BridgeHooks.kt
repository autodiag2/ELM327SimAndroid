package com.github.autodiag2.elm327emu.sim.serial

import java.util.concurrent.LinkedBlockingQueue
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException

class QueueInputStream : InputStream() {
    private val queue =
        LinkedBlockingQueue<ByteArray>()

    private val eof =
        ByteArray(0)

    @Volatile
    private var closed = false

    private var current: ByteArray? = null
    private var currentOffset = 0

    fun offer(
        data: ByteArray
    ) {
        if (closed) {
            return
        }

        if (data.isEmpty()) {
            return
        }

        queue.put(
            data.copyOf()
        )
    }

    override fun read(): Int {
        val buffer = ByteArray(1)

        val count =
            read(
                buffer,
                0,
                1
            )

        if (count < 0) {
            return -1
        }

        return buffer[0].toInt() and 0xFF
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int
    ): Int {
        if (
            offset < 0 ||
            length < 0 ||
            offset > buffer.size - length
        ) {
            throw IndexOutOfBoundsException()
        }

        if (length == 0) {
            return 0
        }

        while (true) {
            if (current == eof) {
                return -1
            }

            val data =
                current

            if (
                data != null &&
                currentOffset < data.size
            ) {
                val count =
                    minOf(
                        length,
                        data.size - currentOffset
                    )

                System.arraycopy(
                    data,
                    currentOffset,
                    buffer,
                    offset,
                    count
                )

                currentOffset += count

                if (currentOffset >= data.size) {
                    current = null
                    currentOffset = 0
                }

                return count
            }

            current = null
            currentOffset = 0

            if (closed && queue.isEmpty()) {
                return -1
            }

            val next =
                try {
                    queue.take()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()

                    throw IOException(
                        "Input interrupted",
                        e
                    )
                }

            if (next.isEmpty()) {
                current = eof
                return -1
            }

            current = next
        }
    }

    override fun close() {
        if (closed) {
            return
        }

        closed = true
        queue.offer(eof)
    }
}

class QueueOutputStream(
    private val target: QueueInputStream
) : OutputStream() {

    @Volatile
    private var closed = false

    override fun write(
        value: Int
    ) {
        write(
            byteArrayOf(
                value.toByte()
            ),
            0,
            1
        )
    }

    override fun write(
        buffer: ByteArray,
        offset: Int,
        length: Int
    ) {
        if (closed) {
            throw IOException(
                "Stream closed"
            )
        }

        if (
            offset < 0 ||
            length < 0 ||
            offset > buffer.size - length
        ) {
            throw IndexOutOfBoundsException()
        }

        if (length == 0) {
            return
        }

        target.offer(
            buffer.copyOfRange(
                offset,
                offset + length
            )
        )
    }

    override fun flush() {
        if (closed) {
            throw IOException(
                "Stream closed"
            )
        }
    }

    override fun close() {
        if (closed) {
            return
        }

        closed = true
        target.close()
    }
}

/**
 * Takes the Bridge or LogReplay streams and feeds into a StateMachine.
 */
class QueueDuplexStreams {

    // Emu side input : Bridge -> Emu
    val input: InputStream = QueueInputStream()

    // Bridge side input : Emu -> Bridge
    val bridgeInput: InputStream = QueueInputStream()

    // Emu side output : Emu -> Bridge
    val output: OutputStream = QueueOutputStream(bridgeInput as QueueInputStream)

    // Bridge side ouput : Bridge -> Emu
    val bridgeOutput: OutputStream = QueueOutputStream(input as QueueInputStream)

    fun close() {
        output.close()
        bridgeOutput.close()
    }
}