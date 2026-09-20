package com.github.autodiag2.elm327emu.sim.serial

import android.util.Log
import com.github.autodiag2.elm327emu.BuildConfig
import com.github.autodiag2.elm327emu.LogLevel
import com.github.autodiag2.elm327emu.sim.serial.CustomController.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.InputStream
import java.io.OutputStream

class StateMachine(
    private val controller: CustomController
) {
    enum class State {
        READY,
        WAIT_DELAY,
        WAIT_RECV,
        FINISHED
    }

    data class Path(
        val id: Int,
        var block: Block,
        var state: State = State.READY,
        var wakeTime: Long = 0L
    )

    /*
     * All interaction with the state machine goes through this channel.
     *
     * The coroutine consuming this channel is the only code allowed to
     * modify paths or execute state transitions.
     */
    private sealed class Event {
        data object Start : Event()
        data object Stop : Event()
        data class Receive(val bytes: ByteArray) : Event()
        data object Tick : Event()
    }

    private val scope =
        CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val events =
        Channel<Event>(Channel.UNLIMITED)

    /*
     * ONLY stateLoop() accesses this list.
     */
    private val paths =
        mutableListOf<Path>()

    private var stateJob: Job? = null
    private var inputJob: Job? = null
    private val inputChunks = Channel<ByteArray>(Channel.UNLIMITED)

    private var nextPathId = 1

    /*
     * True only while the execution graph is running.
     *
     * Accessed outside the state-machine coroutine, therefore volatile.
     */
    @Volatile
    private var running = false

    private val input: InputStream?
        get() = controller.emuOutput

    private val output: OutputStream?
        get() = controller.emuInput

    init {
        stateJob = scope.launch {
            stateLoop()
        }
    }

    private fun startInputReader() {
        inputJob?.cancel()

        inputJob = scope.launch {
            val inputStream = input ?: run {
                appendLog(
                    "no hook installed cannot process",
                    LogLevel.ERROR
                )

                events.trySend(Event.Stop)
                return@launch
            }

            val buffer = ByteArray(512)

            try {
                while (isActive && running) {
                    val count = inputStream.read(buffer)

                    if (count <= 0) {
                        continue
                    }

                    inputChunks.send(
                        buffer.copyOf(count)
                    )
                }
            } catch (_: CancellationException) {
                // Normal shutdown.
            } catch (e: Exception) {
                appendLog(
                    "input reader error: ${e.message}",
                    LogLevel.ERROR
                )

                events.trySend(Event.Stop)
            }
        }

        scope.launch {
            accumulateInput()
        }
    }

    private fun findReceiveDelimiter(
        data: ByteArray
    ): Int {
        for (i in data.indices) {
            if (data[i] != '\r'.code.toByte()) {
                continue
            }

            /*
            * CRLF
            */
            if (
                i + 1 < data.size &&
                data[i + 1] == '\n'.code.toByte()
            ) {
                return i + 2
            }

            /*
            * CR alone
            */
            return i + 1
        }

        return -1
    }

    private suspend fun accumulateInput() {
        val pending =
            java.io.ByteArrayOutputStream()

        for (chunk in inputChunks) {
            pending.write(chunk)

            while (true) {
                val data = pending.toByteArray()

                val end =
                    findReceiveDelimiter(data)

                if (end < 0) {
                    break
                }

                val message =
                    data.copyOfRange(0, end)

                logDebug(
                    "Input message: " +
                        message.toDebugString()
                )

                events.send(
                    Event.Receive(message)
                )

                /*
                * Pop the consumed message.
                */
                pending.reset()

                if (end < data.size) {
                    pending.write(
                        data,
                        end,
                        data.size - end
                    )
                }
            }
        }
    }

    fun getString(
        resId: Int,
        vararg formatArgs: Any?
    ): String {
        return controller.getString(
            resId,
            *formatArgs.map { it ?: "" }.toTypedArray()
        )
    }

    fun appendLog(
        text: String,
        level: LogLevel = LogLevel.DEBUG
    ) {
        controller.activity.appendLog(text, level)
    }

    fun logDebug(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(
                "sim.serial.StateMachine",
                message
            )
        }
    }

    /*
     * Public API.
     *
     * These methods NEVER execute state-machine logic directly.
     * They only enqueue events.
     */

    fun start() {
        events.trySend(Event.Start)
    }

    fun stop() {
        events.trySend(Event.Stop)
    }

    fun onReceive(bytes: ByteArray) {
        events.trySend(
            Event.Receive(bytes.copyOf())
        )
    }

    /*
     * The ONLY owner of paths and execution state.
     */
    private suspend fun stateLoop() {
        for (event in events) {
            when (event) {
                Event.Start -> {
                    handleStart()
                }

                Event.Stop -> {
                    handleStop()
                }

                is Event.Receive -> {
                    handleReceive(event.bytes)
                }

                Event.Tick -> {
                    handleTick()
                }
            }
        }
    }

    private fun handleStart() {
        handleStop()

        /*
         * Execution roots are blocks with NO incoming execution link.
         *
         * Block.parent is the visual/container hierarchy and must not
         * be used to determine execution roots.
         */
        val linkedBlockIds =
            controller.links
                .map { it.to }
                .toSet()

        val initialBlocks =
            controller.blocks
                .filter { it.id !in linkedBlockIds }

        logDebug(
            "Execution roots: " +
                initialBlocks.joinToString(", ") {
                    it.id.toString()
                }
        )

        running = initialBlocks.isNotEmpty()

        for (block in initialBlocks) {
            createPath(block)
        }

        if (running) {
            startInputReader()
            handleTick()
        }
    }

    private fun handleStop() {
        running = false

        inputJob?.cancel()
        inputJob = null

        paths.clear()
        nextPathId = 1
    }

    /*
     * Execute one pass through every active path.
     *
     * This is ONLY called by stateLoop().
     */
    private fun handleTick() {
        if (!running) {
            return
        }

        val now = System.currentTimeMillis()

        for (path in paths.toList()) {
            when (path.state) {
                State.READY -> {
                    execute(path)
                }

                State.WAIT_DELAY -> {
                    if (now >= path.wakeTime) {
                        path.state = State.READY
                        execute(path)
                    }
                }

                State.WAIT_RECV -> {
                    // Waiting for a Receive event.
                }

                State.FINISHED -> {
                    paths.remove(path)
                }
            }
        }

        /*
         * Remove finished paths before deciding whether the graph
         * is still running.
         */
        paths.removeAll {
            it.state == State.FINISHED
        }

        if (paths.isEmpty()) {
            running = false
            inputJob?.cancel()
            inputJob = null
            return
        }

        /*
         * Schedule another state-machine tick.
         *
         * This does NOT execute the state machine directly.
         * It only posts another event.
         */
        scope.launch {
            delay(10L)

            if (isActive && running) {
                events.trySend(Event.Tick)
            }
        }
    }

    private fun execute(path: Path) {
        val block = path.block
        val blockId = block.id
        val blockType = block.type

        logDebug(
            "Executing $blockType " +
                "path=${path.id} " +
                "block=$blockId"
        )

        when (blockType) {
            Block.Type.DELAY -> {
                path.wakeTime =
                    System.currentTimeMillis() +
                        block.delay

                path.state = State.WAIT_DELAY
            }

            Block.Type.SEND -> {
                executeSend(block)
                advance(path)
            }

            Block.Type.RECV -> {
                path.state = State.WAIT_RECV
            }

            Block.Type.CONTAINER -> {
                advance(path)
            }
        }

        logDebug(
            "Execution end " +
                "path=${path.id} " +
                "block=$blockId"
        )
    }

    private fun executeSend(block: Block) {
        var text = block.text

        if (
            block.includeEol &&
            !text.endsWith("\r\n")
        ) {
            text += "\r\n"
        }

        val bytes =
            if (block.interpretEscapes) {
                parseEscapedBytes(text)
            } else {
                text.toByteArray(
                    Charsets.ISO_8859_1
                )
            }

        logDebug(
            "SEND WRITE -> " +
                bytes.toDebugString()
        )

        output!!.write(bytes)
        output!!.flush()

        logDebug(
            "SEND WRITE DONE -> " +
                bytes.toDebugString()
        )
    }

    private fun ByteArray.toDebugString(): String {
        return buildString {
            for (byte in this@toDebugString) {
                val value = byte.toInt() and 0xFF

                when (value) {
                    0x0D -> append("\\r")
                    0x0A -> append("\\n")
                    0x09 -> append("\\t")
                    0x00 -> append("\\0")
                    0x5C -> append("\\\\")
                    else -> {
                        if (value in 0x20..0x7E) {
                            append(value.toChar())
                        } else {
                            append(
                                "\\x%02X".format(value)
                            )
                        }
                    }
                }
            }
        }
    }

    private fun handleReceive(bytes: ByteArray) {
        if (!running) {
            return
        }

        logDebug(
            "RECV ${bytes.size} bytes: " +
                bytes.toDebugString()
        )

        /*
        * Only paths which were already waiting before this receive
        * event can consume it.
        */
        val waitingPaths =
            paths
                .filter {
                    it.state == State.WAIT_RECV
                }
                .toList()

        for (path in waitingPaths) {
            if (matches(path.block, bytes)) {
                logDebug(
                    "RECV matched " +
                        "path=${path.id} " +
                        "block=${path.block.id}"
                )

                advance(path)
            }
        }

        handleTick()
    }

    private fun matches(
        block: Block,
        received: ByteArray
    ): Boolean {
        val expected =
            if (block.interpretEscapes) {
                parseEscapedBytes(block.text)
            } else {
                block.text.toByteArray(
                    Charsets.ISO_8859_1
                )
            }

        logDebug(
            "MATCH block=${block.id} " +
                "mode=${block.match} " +
                "expected=${expected.toDebugString()} " +
                "received=${received.toDebugString()}"
        )

        return when (block.match.lowercase()) {
            "exact" -> {
                received.contentEquals(expected)
            }

            "regex" -> {
                val pattern =
                    if (block.interpretEscapes) {
                        /*
                        * Decode escaped sequences before creating
                        * the regex. For example:
                        *
                        * ATZ\\r
                        *
                        * becomes:
                        *
                        * ATZ + byte 0x0D
                        */
                        parseEscapedBytes(block.text)
                            .toString(Charsets.ISO_8859_1)
                    } else {
                        block.text
                    }

                Regex(pattern)
                    .containsMatchIn(
                        received.toString(
                            Charsets.ISO_8859_1
                        )
                    )
            }

            else -> {
                logDebug(
                    "Unknown match mode '${block.match}', " +
                        "using exact"
                )

                received.contentEquals(expected)
            }
        }
    }

    /*
     * Follow ONLY execution links.
     *
     * Block.parent is deliberately not considered here.
     */
    private fun advance(path: Path) {
        logDebug(
            "advance path=${path.id} " +
                "block=${path.block.id}"
        )

        val fromId = path.block.id

        val nextBlocks =
            controller.links
                .filter { it.from == fromId }
                .mapNotNull { link ->
                    controller.blocks.find {
                        it.id == link.to
                    }
                }

        if (nextBlocks.isEmpty()) {
            path.state = State.FINISHED

            logDebug(
                "path=${path.id} finished"
            )

            return
        }

        /*
         * First outgoing link continues the current path.
         */
        path.block = nextBlocks.first()
        path.state = State.READY

        /*
         * Additional outgoing links represent branches.
         */
        for (block in nextBlocks.drop(1)) {
            createPath(block)
        }
    }

    private fun createPath(block: Block) {
        val path =
            Path(
                id = nextPathId++,
                block = block
            )

        paths.add(path)

        logDebug(
            "createPath " +
                "path=${path.id} " +
                "block=${block.id}"
        )
    }

    fun isRunning(): Boolean {
        return running
    }

    private fun parseEscapedBytes(
        text: String
    ): ByteArray {
        val output =
            java.io.ByteArrayOutputStream()

        var i = 0

        while (i < text.length) {
            val c = text[i]

            if (c != '\\') {
                output.write(c.code)
                i++
                continue
            }

            if (i + 1 >= text.length) {
                output.write('\\'.code)
                i++
                continue
            }

            when (text[i + 1]) {
                'r' -> {
                    output.write('\r'.code)
                    i += 2
                }

                'n' -> {
                    output.write('\n'.code)
                    i += 2
                }

                't' -> {
                    output.write('\t'.code)
                    i += 2
                }

                '\\' -> {
                    output.write('\\'.code)
                    i += 2
                }

                '0' -> {
                    output.write(0)
                    i += 2
                }

                'x' -> {
                    if (i + 3 < text.length) {
                        val hex =
                            text.substring(
                                i + 2,
                                i + 4
                            )

                        val value =
                            hex.toIntOrNull(16)

                        if (value != null) {
                            output.write(value)
                            i += 4
                        } else {
                            output.write('\\'.code)
                            i++
                        }
                    } else {
                        output.write('\\'.code)
                        i++
                    }
                }

                else -> {
                    /*
                     * Preserve unknown escapes.
                     * Example: "\q" remains "\q".
                     */
                    output.write('\\'.code)
                    i++
                }
            }
        }

        return output.toByteArray()
    }

    private fun String.escape(): String {
        return this
            .replace("\\", "\\\\")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\t", "\\t")
    }

    fun destroy() {
        running = false

        inputJob?.cancel()
        inputJob = null

        stateJob?.cancel()
        stateJob = null

        events.close()
        scope.cancel()
    }
}