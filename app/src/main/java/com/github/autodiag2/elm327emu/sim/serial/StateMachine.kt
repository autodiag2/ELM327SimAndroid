package com.github.autodiag2.elm327emu.sim.serial

import android.util.Log
import com.github.autodiag2.elm327emu.BuildConfig
import com.github.autodiag2.elm327emu.LogLevel
import com.github.autodiag2.elm327emu.sim.serial.CustomController.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeoutException
import com.github.autodiag2.elm327emu.sim.EmuInterface

class StateMachine(
    private val controller: CustomController,
    private val listener: Listener? = null
): EmuInterface() {
    enum class State {
        READY,
        WAIT_DELAY,
        WAIT_RECV,
        FINISHED
    }

    data class Path(
        val id: Int,
        var block: BlockController,
        var state: State = State.READY,
        var wakeTime: Long = 0L
    )

    private sealed class Event {
        data object Start : Event()
        data object Stop : Event()
        data object ModelChanged : Event()
        data class Receive(val bytes: ByteArray) : Event()
        data class Timeout(val pathId: Int) : Event()
        data object Tick : Event()
    }

    interface Listener {
        fun onBlockStateChanged(
            block: BlockController,
            state: BlockController.State
        )
    }

    private val scope =
        CoroutineScope(
            Dispatchers.IO +
                SupervisorJob()
        )

    private val events =
        Channel<Event>(Channel.UNLIMITED)

    private val paths =
        mutableListOf<Path>()

    private var stateJob: Job? = null
    private var inputJob: Job? = null

    private val inputChunks =
        Channel<ByteArray>(
            Channel.UNLIMITED
        )

    private var nextPathId = 1

    @Volatile
    private var running = false

    init {
        stateJob =
            scope.launch {
                stateLoop()
            }
    }

    private fun setBlockState(
        block: BlockController,
        state: BlockController.State
    ) {
        listener?.onBlockStateChanged(
            block,
            state
        )
    }

    private fun startInputReader() {
        inputJob?.cancel()

        inputJob =
            scope.launch {
                val buffer =
                    ByteArray(512)

                try {
                    while (
                        isActive &&
                        running
                    ) {
                        val count = recv(buffer)

                        logDebug(
                            "received ${count} bytes"
                        )

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

                    events.trySend(
                        Event.Stop
                    )
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
            if (
                data[i] !=
                '\r'.code.toByte()
            ) {
                continue
            }

            if (
                i + 1 < data.size &&
                data[i + 1] ==
                '\n'.code.toByte()
            ) {
                return i + 2
            }

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
                val data =
                    pending.toByteArray()

                val end =
                    findReceiveDelimiter(
                        data
                    )

                if (end < 0) {
                    break
                }

                val message =
                    data.copyOfRange(
                        0,
                        end
                    )

                logDebug(
                    "Input message: " +
                        message.toDebugString()
                )

                events.send(
                    Event.Receive(
                        message
                    )
                )

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
            *formatArgs.map {
                it ?: ""
            }.toTypedArray()
        )
    }

    fun appendLog(
        text: String,
        level: LogLevel = LogLevel.DEBUG
    ) {
        controller.activity.appendLog(
            text,
            level
        )
    }

    fun logDebug(
        message: String
    ) {
        if (BuildConfig.DEBUG) {
            Log.d(
                "sim.serial.StateMachine",
                message
            )
        }
    }

    fun start(hookBridgeStreams: QueueDuplexStreams? = null) {
        events.trySend(
            Event.Start
        )
    }

    fun stop() {
        logDebug("STOPPING")
        events.trySend(
            Event.Stop
        )
    }

    fun customModelChanged() {
        events.trySend(
            Event.ModelChanged
        )
    }

    private suspend fun stateLoop() {
        for (event in events) {
            when (event) {
                Event.Start -> {
                    handleStart()
                }

                Event.Stop -> {
                    handleStop()
                }

                Event.ModelChanged -> {
                    handleModelChanged()
                }

                is Event.Receive -> {
                    handleReceive(
                        event.bytes
                    )
                }

                is Event.Timeout -> {
                    handleTimeout(
                        event.pathId
                    )
                }

                Event.Tick -> {
                    handleTick()
                }
            }
        }
    }

    fun containerGetRootBlocks(container: BlockController? = null): List<BlockController> {
        val linkedBlockIds =
            controller.links
                .map { it.to }
                .toSet()
        
        return controller.blocks
                .filter { block ->
                    block.id !in linkedBlockIds &&
                        block.parent == container
                }
    }

    private suspend fun handleStart() {
        handleStop()

        controller.resetBlockStates()
        val initialBlocks = containerGetRootBlocks()

        logDebug(
            "Execution roots: " +
                initialBlocks.joinToString(", ") {
                    it.id.toString()
                }
        )

        running =
            initialBlocks.isNotEmpty()

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
        close()
        logDebug("STOPPED")
    }

    /**
     * Synchronize the running state machine with the current
     * CustomController model.
     *
     * This method is called only from stateLoop(), therefore
     * paths cannot be modified concurrently with execution.
     */
    private suspend fun handleModelChanged() {
        if (!running) {
            return
        }

        logDebug(
            "Custom model changed"
        )

        val currentBlocks =
            controller.blocks
                .associateBy {
                    it.id
                }

        /*
         * Remove paths whose current block no longer exists.
         */
        val removedPaths =
            paths.filter {
                !currentBlocks.containsKey(
                    it.block.id
                )
            }

        for (path in removedPaths) {
            logDebug(
                "Removing path=${path.id}: " +
                    "block=${path.block.id} was deleted"
            )

            setBlockState(
                path.block,
                BlockController.State.IDLE
            )

            path.state =
                State.FINISHED
        }

        paths.removeAll {
            it.state == State.FINISHED
        }

        /*
         * Keep only one active path per current block.
         *
         * This prevents repeated model changes from creating
         * duplicate paths for the same execution root.
         */
        val duplicatePaths =
            mutableSetOf<Int>()

        val duplicatePathObjects =
            paths.filter { path ->
                !duplicatePaths.add(
                    path.block.id
                )
            }

        for (path in duplicatePathObjects) {
            logDebug(
                "Removing duplicate path=${path.id}: " +
                    "block=${path.block.id}"
            )

            path.state =
                State.FINISHED
        }

        paths.removeAll {
            it.state == State.FINISHED
        }

        /*
         * Recalculate execution roots.
         *
         * A root is a block with no incoming execution link.
         */
        val linkedBlockIds =
            controller.links
                .map {
                    it.to
                }
                .toSet()

        val rootBlocks =
            controller.blocks
                .filter {
                    it.id !in linkedBlockIds
                }

        /*
         * Add roots which do not currently have a path.
         *
         * This is important when a new block is added, or when
         * removing an incoming link turns an existing block into
         * a root.
         */
        val pathBlockIds =
            paths
                .map {
                    it.block.id
                }
                .toMutableSet()

        for (root in rootBlocks) {
            if (
                pathBlockIds.add(
                    root.id
                )
            ) {
                logDebug(
                    "Model change added execution root: " +
                        "block=${root.id}"
                )

                createPath(root)
            }
        }

        /*
         * Update paths whose current block changed while the
         * model was being edited.
         *
         * DELAY:
         * restart the delay using the new timeout.
         *
         * RECV:
         * root receives remain infinite;
         * non-root receives get a new timeout.
         *
         * SEND and CONTAINER:
         * their changed parameters are used the next time
         * the block executes, so no state change is necessary.
         */
        val now =
            System.currentTimeMillis()

        val linkedIds =
            controller.links
                .map {
                    it.to
                }
                .toSet()

        for (path in paths.toList()) {
            if (
                !currentBlocks.containsKey(
                    path.block.id
                )
            ) {
                continue
            }

            when (path.state) {
                State.WAIT_DELAY -> {
                    val block =
                        currentBlocks[
                            path.block.id
                        ] ?: continue

                    if (
                        block.type !=
                        BlockController.Type.DELAY
                    ) {
                        path.state =
                            State.READY

                        path.wakeTime = 0L

                        setBlockState(
                            block,
                            BlockController.State.IDLE
                        )

                        continue
                    }

                    path.wakeTime =
                        now +
                            block.timeoutMs

                    logDebug(
                        "Updated DELAY path=" +
                            "${path.id} " +
                            "block=${block.id} " +
                            "wakeTime=${path.wakeTime}"
                    )
                }

                State.WAIT_RECV -> {
                    val block =
                        currentBlocks[
                            path.block.id
                        ] ?: continue

                    if (
                        block.type !=
                        BlockController.Type.RECV
                    ) {
                        path.state =
                            State.READY

                        path.wakeTime = 0L

                        setBlockState(
                            block,
                            BlockController.State.IDLE
                        )

                        continue
                    }

                    val isRoot =
                        block.id !in linkedIds

                    path.wakeTime =
                        if (isRoot) {
                            Long.MAX_VALUE
                        } else {
                            now +
                                block.timeoutMs
                        }

                    logDebug(
                        "Updated RECV path=" +
                            "${path.id} " +
                            "block=${block.id} " +
                            "root=$isRoot " +
                            "wakeTime=${path.wakeTime}"
                    )
                }

                State.READY,
                State.FINISHED -> {
                    // Nothing to update.
                }
            }
        }

        /*
         * If there are no paths left, stop execution.
         */
        if (paths.isEmpty()) {
            logDebug(
                "No execution paths remain after model change"
            )

            running = false

            inputJob?.cancel()
            inputJob = null

            return
        }

        /*
         * Make sure the execution loop continues after a model
         * change, including when a new root was added.
         */
        handleTick()
    }

    private suspend fun handleTick() {
        if (!running) {
            return
        }

        val now =
            System.currentTimeMillis()

        for (path in paths.toList()) {
            when (path.state) {
                State.READY -> {
                    execute(path)
                }

                State.WAIT_DELAY -> {
                    if (
                        now >=
                        path.wakeTime
                    ) {
                        path.state =
                            State.READY

                        setBlockState(
                            path.block,
                            BlockController.State.SUCCESS
                        )

                        advance(path)
                    }
                }

                State.WAIT_RECV -> {
                    if (
                        now >=
                        path.wakeTime
                    ) {
                        events.trySend(
                            Event.Timeout(
                                path.id
                            )
                        )
                    }
                }

                State.FINISHED -> {
                    paths.remove(path)
                }
            }
        }

        paths.removeAll {
            it.state ==
                State.FINISHED
        }

        if (paths.isEmpty()) {
            running = false

            inputJob?.cancel()
            inputJob = null

            return
        }

        scope.launch {
            delay(10L)

            if (
                isActive &&
                running
            ) {
                events.trySend(
                    Event.Tick
                )
            }
        }
    }

    private suspend fun execute(
        path: Path
    ) {
        val block =
            path.block

        val blockId =
            block.id

        val blockType =
            block.type

        logDebug(
            "Executing $blockType " +
                "path=${path.id} " +
                "block=$blockId"
        )

        setBlockState(
            block,
            BlockController.State.IN_PROGRESS
        )

        try {
            when (blockType) {
                BlockController.Type.DELAY -> {
                    path.wakeTime =
                        System.currentTimeMillis() +
                            block.timeoutMs

                    path.state =
                        State.WAIT_DELAY
                }

                BlockController.Type.SEND -> {
                    executeSend(block)

                    setBlockState(
                        block,
                        BlockController.State.SUCCESS
                    )

                    advance(path)
                }

                BlockController.Type.RECV -> {
                    val linkedBlockIds =
                        controller.links
                            .map {
                                it.to
                            }
                            .toSet()

                    val isRoot =
                        block.id !in linkedBlockIds

                    if (isRoot) {
                        path.wakeTime =
                            Long.MAX_VALUE
                    } else {
                        path.wakeTime =
                            System.currentTimeMillis() +
                                block.timeoutMs
                    }

                    path.state =
                        State.WAIT_RECV
                }

                BlockController.Type.CONTAINER -> {
                    setBlockState(
                        block,
                        BlockController.State.IN_PROGRESS
                    )

                    val children =
                        block.children

                    if (children.isEmpty()) {
                        setBlockState(
                            block,
                            BlockController.State.SUCCESS
                        )

                        advance(path)
                    } else {
                        path.state = State.FINISHED

                        val childRoots = containerGetRootBlocks(block)
                        for (child in childRoots) {
                            createPath(child)
                        }

                        paths.removeAll {
                            it.state == State.FINISHED
                        }
                    }
                }
            }
        } catch (
            e: TimeoutCancellationException
        ) {
            appendLog(
                "$blockType timeout: " +
                    "block=$blockId " +
                    "timeout=${block.timeoutMs}ms",
                LogLevel.ERROR
            )

            logDebug(
                "$blockType TIMEOUT " +
                    "path=${path.id} " +
                    "block=$blockId"
            )

            setBlockState(
                block,
                BlockController.State.FAILED
            )

            path.state =
                State.FINISHED
        } catch (e: Exception) {
            appendLog(
                "$blockType error: " +
                    "block=$blockId: ${e.message}",
                LogLevel.ERROR
            )

            logDebug(
                "$blockType ERROR " +
                    "path=${path.id} " +
                    "block=$blockId: ${e.message}"
            )

            setBlockState(
                block,
                BlockController.State.FAILED
            )

            path.state =
                State.FINISHED
        }

        logDebug(
            "Execution end " +
                "path=${path.id} " +
                "block=$blockId"
        )
    }

    private fun isDescendant(
        block: BlockController,
        container: BlockController
    ): Boolean {
        var current = block.parent

        while (current != null) {
            if (current === container) {
                return true
            }

            current = current.parent
        }

        return false
    }

    private suspend fun executeSend(
        block: BlockController
    ) {
        var text =
            block.text

        if (
            block.includeEol &&
            !text.endsWith("\r\n")
        ) {
            text += "\r\n"
        }

        val bytes =
            if (block.interpretEscapes) {
                BlockController.parseEscapedBytes(
                    text
                )
            } else {
                text.toByteArray(
                    Charsets.ISO_8859_1
                )
            }

        logDebug(
            "SEND WRITE -> " +
                bytes.toDebugString()
        )

        send(bytes, bytes.size, block.timeoutMs + 0L)

        logDebug(
            "SEND WRITE DONE -> " +
                bytes.toDebugString()
        )
    }

    private fun ByteArray.toDebugString(): String {
        return buildString {
            for (
                byte in this@toDebugString
            ) {
                val value =
                    byte.toInt() and 0xFF

                when (value) {
                    0x0D ->
                        append("\\r")

                    0x0A ->
                        append("\\n")

                    0x09 ->
                        append("\\t")

                    0x00 ->
                        append("\\0")

                    0x5C ->
                        append("\\\\")

                    else -> {
                        if (
                            value in
                            0x20..0x7E
                        ) {
                            append(
                                value.toChar()
                            )
                        } else {
                            append(
                                "\\x%02X".format(
                                    value
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun handleReceive(
        bytes: ByteArray
    ) {
        if (!running) {
            return
        }

        logDebug(
            "RECV ${bytes.size} bytes: " +
                bytes.toDebugString()
        )

        val waitingPaths =
            paths
                .filter {
                    it.state ==
                        State.WAIT_RECV
                }
                .toList()

        for (path in waitingPaths) {
            if (
                path.block.recvMatches(
                    bytes
                )
            ) {
                logDebug(
                    "RECV matched " +
                        "path=${path.id} " +
                        "block=${path.block.id}"
                )

                setBlockState(
                    path.block,
                    BlockController.State.SUCCESS
                )

                advance(path)
            } else {
                val linkedBlockIds =
                    controller.links
                        .map {
                            it.to
                        }
                        .toSet()

                val isRoot =
                    path.block.id !in
                        linkedBlockIds

                if (isRoot) {
                    setBlockState(
                        path.block,
                        BlockController.State.IN_PROGRESS
                    )

                    logDebug(
                        "RECV failed for root " +
                            "path=${path.id} " +
                            "block=${path.block.id}, " +
                            "keeping WAIT_RECV"
                    )
                } else {
                    setBlockState(
                        path.block,
                        BlockController.State.FAILED
                    )

                    path.state =
                        State.FINISHED
                }
            }
        }

        paths.removeAll {
            it.state ==
                State.FINISHED
        }

        if (paths.isEmpty()) {
            running = false

            inputJob?.cancel()
            inputJob = null

            return
        }

        handleTick()
    }

    private fun handleTimeout(
        pathId: Int
    ) {
        if (!running) {
            return
        }

        val path =
            paths.find {
                it.id == pathId
            } ?: return

        if (
            path.state !=
            State.WAIT_RECV
        ) {
            return
        }

        val exception =
            TimeoutException(
                "RECV timeout for " +
                    "block=${path.block.id}"
            )

        appendLog(
            exception.message
                ?: "RECV timeout",
            LogLevel.ERROR
        )

        logDebug(
            "RECV TIMEOUT " +
                "path=${path.id} " +
                "block=${path.block.id}"
        )

        setBlockState(
            path.block,
            BlockController.State.FAILED
        )

        path.state =
            State.FINISHED

        paths.removeAll {
            it.state ==
                State.FINISHED
        }

        if (paths.isEmpty()) {
            running = false

            inputJob?.cancel()
            inputJob = null
        }
    }

    private fun advance(
        path: Path
    ) {
        logDebug(
            "advance path=${path.id} " +
                "block=${path.block.id}"
        )

        val fromId =
            path.block.id

        val nextBlocks =
            controller.links
                .filter {
                    it.from == fromId
                }
                .mapNotNull { link ->
                    controller.blocks.find {
                        it.id == link.to
                    }
                }

        if (nextBlocks.isEmpty()) {
            onPathEnded(path)
            return
        }

        path.block =
            nextBlocks.first()

        path.state =
            State.READY

        for (
            block in
            nextBlocks.drop(1)
        ) {
            createPath(block)
        }
    }

    private fun onPathEnded(
        path: Path
    ) {
        logDebug(
            "path=${path.id} ended at " +
                "block=${path.block.id}"
        )

        val linkedBlockIds =
            controller.links
                .map {
                    it.to
                }
                .toSet()

        val rootBlocks =
            controller.blocks
                .filter {
                    it.id !in linkedBlockIds
                }

        if (rootBlocks.isEmpty()) {
            path.state =
                State.FINISHED

            return
        }

        logDebug(
            "path=${path.id} restarting with roots: " +
                rootBlocks.joinToString(", ") {
                    it.id.toString()
                }
        )

        path.block =
            rootBlocks.first()

        path.state =
            State.READY

        for (
            block in
            rootBlocks.drop(1)
        ) {
            createPath(block)
        }
    }

    private fun resolveBlockId(id: Int): BlockController? {
        return controller.blocks.find { it.id == id }
    }

    private fun createPath(
        blockAny: Any
    ) {
        val block =
            when (blockAny) {
                is Int ->
                    resolveBlockId(blockAny)
                        ?: throw IllegalArgumentException(
                            "Block not found: id=$blockAny"
                        )

                is BlockController ->
                    blockAny

                else ->
                    throw IllegalArgumentException(
                        "Invalid block type: ${blockAny::class.java.name}"
                    )
            }
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

    private fun String.escape(): String {
        return this
            .replace(
                "\\",
                "\\\\"
            )
            .replace(
                "\r",
                "\\r"
            )
            .replace(
                "\n",
                "\\n"
            )
            .replace(
                "\t",
                "\\t"
            )
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