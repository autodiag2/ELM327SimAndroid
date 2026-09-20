package com.github.autodiag2.elm327emu.sim.serial

import com.github.autodiag2.elm327emu.sim.serial.CustomController
import com.github.autodiag2.elm327emu.sim.serial.CustomController.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import com.github.autodiag2.elm327emu.LogLevel

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

    fun getString(resId: Int, vararg formatArgs: Any?): String {
        return controller.getString(resId, *formatArgs.map { it ?: "" }.toTypedArray())
    }

    fun appendLog(text: String, level: LogLevel = LogLevel.DEBUG) {
        controller.activity.appendLog(text, level)
    }

    private var nextPathId = 1

    private val paths = mutableListOf<Path>()
    private val input: InputStream?
        get() = controller.emuOutput

    private val output: OutputStream?
        get() = controller.emuInput

    /**
     * Start execution from every top-level block.
     */
    fun start() {
        stop()

        val initialBlocks =
            controller.blocks
                .filter { it.parent == null }

        for (block in initialBlocks) {
            createPath(block)
        }
        process()
    }

    fun stop() {
        paths.clear()
    }

    fun isRunning(): Boolean =
        paths.isNotEmpty()

    private fun parseEscapedBytes(text: String): ByteArray {

        val result = ByteArrayOutputStream()
        var i = 0

        while (i < text.length) {

            if (text[i] != '\\') {
                result.write(
                    text[i].code and 0xFF
                )
                i++
                continue
            }

            // Trailing '\'
            if (i + 1 >= text.length) {
                result.write('\\'.code)
                i++
                continue
            }

            when (text[i + 1]) {

                'r' -> {
                    result.write('\r'.code)
                    i += 2
                }

                'n' -> {
                    result.write('\n'.code)
                    i += 2
                }

                't' -> {
                    result.write('\t'.code)
                    i += 2
                }

                '\\' -> {
                    result.write('\\'.code)
                    i += 2
                }

                '0' -> {
                    result.write(0)
                    i += 2
                }

                'x' -> {
                    // \xNN
                    if (i + 3 < text.length) {

                        val hex =
                            text.substring(i + 2, i + 4)

                        val value =
                            hex.toIntOrNull(16)

                        if (value != null) {
                            result.write(value)
                            i += 4
                        } else {
                            result.write('\\'.code)
                            i++
                        }

                    } else {
                        result.write('\\'.code)
                        i++
                    }
                }

                else -> {
                    // Unknown escape: preserve the '\'
                    result.write('\\'.code)
                    i++
                }
            }
        }

        return result.toByteArray()
    }

    /**
     * Called periodically, e.g. from a Handler/Runnable.
     */
    fun process() {
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
                    // Nothing to do.
                    // The path will be resumed by onReceive().
                }

                State.FINISHED -> {
                    paths.remove(path)
                }
            }
        }
    }

    /**
     * Execute the current block of one path.
     */
    private fun execute(path: Path) {

        when (val block = path.block.type) {

            Block.Type.DELAY -> {
                val delayBlock = path.block

                path.wakeTime =
                    System.currentTimeMillis() +
                    delayBlock.delay.toLong()

                path.state = State.WAIT_DELAY
            }

            Block.Type.SEND -> {
                val sendBlock = path.block
                var sendText = sendBlock.text
                val eol = "\r\n"

                if (sendBlock.includeEol && !sendText.endsWith(eol)) {
                    sendText += eol
                }

                val sendBytes: ByteArray
                if (sendBlock.interpretEscapes) {
                    sendBytes = parseEscapedBytes(sendText)
                } else {
                    sendBytes = sendText.toByteArray(Charsets.ISO_8859_1)
                }

                output?.write(sendBytes)
                output?.flush()

                advance(path)
            }

            Block.Type.RECV -> {
                path.state = State.WAIT_RECV
                if ( input == null ) {
                    appendLog("no hook installed cannot process", LogLevel.ERROR)
                } else {
                    val buffer = ByteArray(512)
                    val count = input?.read(buffer) ?: 0

                    val bytes = if (count > 0) {
                        buffer.copyOf(count)
                    } else {
                        ByteArray(0)
                    }
                    advance(path)
                }
            }

            Block.Type.CONTAINER -> {
                /*
                 * Containers are structural only.
                 *
                 * Their children are already represented as graph
                 * nodes. The container itself does not execute.
                 */
                advance(path)
            }
        }
    }

    /**
     * Continue a path through all outgoing links.
     *
     * If there are multiple outgoing links, the current path follows
     * the first one and additional branches get their own Path.
     */
    private fun advance(path: Path) {

        val fromId = path.block.id

        val nextBlocks =
            controller.links
                .filter { it.from == fromId }
                .mapNotNull { link ->
                    controller.blocks.find { it.id == link.to }
                }

        if (nextBlocks.isEmpty()) {
            path.state = State.FINISHED
            return
        }

        /*
         * Continue the current path with the first branch.
         */
        path.block = nextBlocks.first()
        path.state = State.READY

        /*
         * Create independent paths for additional branches.
         */
        for (block in nextBlocks.drop(1)) {
            createPath(block)
        }

        /*
         * Immediately process the new block.
         */
        execute(path)
    }

    /**
     * Feed an incoming message to all paths waiting on RECV.
     */
    fun onReceive(text: String) {

        for (path in paths.toList()) {

            if (path.state != State.WAIT_RECV)
                continue

            val block = path.block

            if (matches(block, text)) {
                path.state = State.READY
                execute(path)
            }
        }
    }

    private fun matches(
        block: Block,
        received: String
    ): Boolean {

        return when (block.match) {

            "exact" ->
                received == block.text

            "contains" ->
                received.contains(block.text)

            "prefix" ->
                received.startsWith(block.text)

            else ->
                received == block.text
        }
    }

    private fun createPath(block: Block): Path {

        val path =
            Path(
                id = nextPathId++,
                block = block
            )

        paths.add(path)

        return path
    }
}