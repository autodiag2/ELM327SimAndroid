package com.github.autodiag2.elm327emu.sim.serial

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.setPadding
import org.json.JSONArray
import org.json.JSONObject
import com.github.autodiag2.elm327emu.R
import android.util.Log
import com.github.autodiag2.elm327emu.BuildConfig
import com.github.autodiag2.elm327emu.ui.JsonConfigurable
import com.github.autodiag2.elm327emu.MainActivity
import android.content.Intent
import com.github.autodiag2.elm327emu.LogLevel
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import com.github.autodiag2.elm327emu.sim.EmuInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

const val SCHEMA = "autodiag/sim/elm327/serialscript"
const val VERSION = 1.0

private class QueueInputStream : InputStream() {
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

private class QueueOutputStream(
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

class QueueDuplexStreams {
    // StateMachine -> Bluetooth
    private val toBluetooth =
        QueueInputStream()

    // Bluetooth -> StateMachine
    private val fromBluetooth =
        QueueInputStream()

    val input: InputStream =
        fromBluetooth

    val output: OutputStream =
        QueueOutputStream(
            toBluetooth
        )

    // Endpoints used by EmuInterface
    val bridgeInput: InputStream =
        toBluetooth

    val bridgeOutput: OutputStream =
        QueueOutputStream(
            fromBluetooth
        )

    fun close() {
        output.close()
        bridgeOutput.close()
    }
}

class CustomController(
    public val activity: MainActivity
) : LinearLayout(activity),
    CustomView.Listener,
    JsonConfigurable,
    StateMachine.Listener {

    public var view: CustomView

    private val stateMachine =
        StateMachine(
            this,
            this
        )

    private val scope =
        CoroutineScope(
            Dispatchers.IO +
                SupervisorJob()
        )

    public val blocks =
        mutableListOf<BlockController>()

    public val links =
        mutableListOf<LinkController>()

    val selectedBlocks =
        mutableSetOf<Int>()

    val selectedLinks =
        mutableSetOf<Pair<Int, Int>>()

    private val blockStates =
        mutableMapOf<
            Int,
            StateMachine.BlockState
        >()

    var emuStreams:
        QueueDuplexStreams? = null

    init {
        orientation = VERTICAL

        LayoutInflater.from(activity).inflate(
            R.layout.sim_custom_serial_screen,
            this,
            true
        )

        view =
            findViewById(
                R.id.custom_serial_view
            )

        view.model = this
    }

    // ------------ Data change ------------

    /**
     * Called whenever the script model has changed.
     *
     * This is deliberately separate from selection and runtime
     * state changes.
     */
    public fun onDataChanged() {
        stateMachine.customModelChanged()
        view.refresh()
    }

    // ------------ listener state machine ------------

    override fun onBlockStateChanged(
        block: BlockController,
        state: StateMachine.BlockState
    ) {
        blockStates[block.id] = state

        activity.runOnUiThread {
            view.refresh()
        }
    }

    fun getBlockState(
        block: BlockController
    ): StateMachine.BlockState {
        return blockStates[block.id]
            ?: StateMachine.BlockState.IDLE
    }

    fun resetBlockStates() {
        blockStates.clear()
        view.refresh()
    }

    // ------------ Listeners of view ------------

    override fun onBlockClicked(
        block: BlockView
    ) {
        block.model!!.edit()
        onDataChanged()
    }

    override fun onLinkToBlock(
        from: BlockView,
        to: BlockView
    ) {
        val linkModel =
            LinkController(
                from.model!!.id,
                to.model!!.id
            )

        val linkView =
            view.addLink(
                model = linkModel
            )

        linkModel.view = linkView

        links.add(linkModel)

        onDataChanged()
    }

    override fun onBlockIncluded(
        parent: BlockView,
        child: BlockView
    ) {
        assert(parent != child)

        if (
            !parent.model!!.children.contains(
                child.model!!.id
            )
        ) {
            parent.model!!.children.add(
                child.model!!.id
            )
        }

        child.model!!.parent =
            parent.model!!

        debugBlockTree()
        onDataChanged()
    }

    override fun onBlockExcluded(
        parent: BlockView,
        child: BlockView
    ) {
        assert(parent != child)

        parent.model!!.children.remove(
            child.model!!.id
        )

        child.model!!.parent = null

        onDataChanged()
    }

    override fun onElementSelected(
        element: ElementView<*>
    ) {
        when (element) {
            is BlockView -> {
                selectedBlocks.add(
                    element.model!!.id
                )
            }

            is LinkView -> {
                val link =
                    element.model!!

                selectedLinks.add(
                    Pair(
                        link.from,
                        link.to
                    )
                )
            }
        }
    }

    override fun onElementUnselected(
        element: ElementView<*>
    ) {
        when (element) {
            is BlockView -> {
                selectedBlocks.remove(
                    element.model!!.id
                )
            }

            is LinkView -> {
                val link =
                    element.model!!

                selectedLinks.remove(
                    Pair(
                        link.from,
                        link.to
                    )
                )
            }
        }
    }

    override fun onUnselectAll() {
        selectedBlocks.clear()
        selectedLinks.clear()
    }

    // ------------ End Listeners view ------------

    // ------------ StateMachine ------------

    fun startScript() {
        scope.launch {
            val emu =
                activity.bridgeOrchestrator
                    as EmuInterface

            val streams =
                QueueDuplexStreams()

            emuStreams = streams

            emu.emuHookStreams(
                streams.bridgeInput,
                streams.bridgeOutput
            )

            stateMachine.start()
        }
    }

    fun stopScript() {
        scope.launch {
            val emu =
                activity.bridgeOrchestrator
                    as EmuInterface

            stateMachine.stop()

            emu.emuUnHookStreams()

            emuStreams?.close()
            emuStreams = null
        }
    }

    // ------------ End StateMachine ------------

    fun onRunStateChange(
        state: Boolean
    ) {
        if (state) {
            startScript()
        } else {
            stopScript()
        }
    }

    fun isBlockSelected(
        block: Any?
    ): Boolean {
        val blockId =
            when (block) {
                is Int ->
                    block

                is BlockController ->
                    block.id

                null ->
                    return false

                else ->
                    return false
            }

        return selectedBlocks.contains(
            blockId
        )
    }

    fun isLinkSelected(
        link: Any?
    ): Boolean {
        val linkKey =
            when (link) {
                is Pair<*, *> -> {
                    val from =
                        link.first as? Int
                            ?: return false

                    val to =
                        link.second as? Int
                            ?: return false

                    Pair(
                        from,
                        to
                    )
                }

                is Int -> {
                    val linkModel =
                        links.find {
                            it.id == link
                        } ?: return false

                    Pair(
                        linkModel.from,
                        linkModel.to
                    )
                }

                is LinkController ->
                    Pair(
                        link.from,
                        link.to
                    )

                null ->
                    return false

                else ->
                    return false
            }

        return selectedLinks.contains(
            linkKey
        )
    }

    fun toggleBlockSelection(
        block: BlockController
    ) {
        if (!selectedBlocks.add(block.id)) {
            selectedBlocks.remove(block.id)
        }

        view.refresh()
    }

    fun toggleLinkSelection(
        link: LinkController
    ) {
        val key =
            Pair(
                link.from,
                link.to
            )

        if (!selectedLinks.add(key)) {
            selectedLinks.remove(key)
        }

        view.refresh()
    }

    fun clearSelection() {
        selectedBlocks.clear()
        selectedLinks.clear()
        view.refresh()
    }

    fun hasSelection(): Boolean {
        return selectedBlocks.isNotEmpty() ||
            selectedLinks.isNotEmpty()
    }

    public fun logDebug(
        message: String
    ) {
        if (BuildConfig.DEBUG) {
            Log.d(
                "sim.serial.Custom",
                message
            )
        }
    }

    fun getString(
        resId: Int,
        vararg formatArgs: Any?
    ): String {
        return activity.getString(
            resId,
            *formatArgs.map {
                it ?: ""
            }.toTypedArray()
        )
    }

    public fun clear() {
        blocks.clear()
        links.clear()
        onUnselectAll()

        onDataChanged()
    }

    public fun clearWithDialog() {
        AlertDialog.Builder(
            activity
        )
            .setTitle(
                R.string.sim_custom_serial_script_clear_confirm
            )
            .setPositiveButton(
                android.R.string.ok
            ) { _, _ ->
                clear()
            }
            .setNegativeButton(
                android.R.string.cancel,
                null
            )
            .show()
    }

    private fun rmLink(
        link: Any
    ) {
        var linko = link

        if (link is Int) {
            if (0 < link) {
                linko =
                    links.find {
                        it.id == link
                    } as LinkController
            }
        }

        assert(
            linko is LinkController
        )

        val linkm =
            linko as LinkController

        selectedLinks.remove(
            Pair(
                linkm.from,
                linkm.to
            )
        )

        links.remove(linkm)

        onDataChanged()
    }

    private fun rmBlock(
        block: Any
    ) {
        var blocko = block

        if (block is Int) {
            if (0 < block) {
                blocko =
                    blocks.find {
                        it.id == block
                    } as BlockController
            }
        }

        assert(
            blocko is BlockController
        )

        val blockm =
            blocko as BlockController

        for (
            childblock in
            blockm.children.toList()
        ) {
            rmBlock(childblock)
        }

        if (blockm.parent != null) {
            blockm.parent!!.children.removeAll {
                it == blockm.id
            }

            blockm.parent = null
        }

        blocks.remove(blockm)

        if (isBlockSelected(blockm)) {
            toggleBlockSelection(blockm)
        }

        for (link in links.toList()) {
            if (
                link.from == blockm.id ||
                link.to == blockm.id
            ) {
                rmLink(link)
            }
        }

        onDataChanged()
    }

    private fun addBlock(
        type: BlockController.Type,
        to: BlockController? = null,
        name: String = ""
    ) {
        var blockName = name

        if (name.isEmpty()) {
            blockName =
                when (type) {
                    BlockController.Type.DELAY ->
                        getString(
                            R.string.sim_custom_serial_script_block_name_delay
                        )

                    BlockController.Type.RECV ->
                        getString(
                            R.string.sim_custom_serial_script_block_name_recv
                        )

                    BlockController.Type.SEND ->
                        getString(
                            R.string.sim_custom_serial_script_block_name_send
                        )

                    BlockController.Type.CONTAINER ->
                        getString(
                            R.string.sim_custom_serial_script_block_name_container
                        )
                }
        }

        val block =
            BlockController(
                type = type,
                name = blockName
            )

        val blockView =
            view.addBlock(
                model = block
            )

        block.viewLink(
            blockView
        )

        blocks.add(block)

        if (
            to?.type ==
            BlockController.Type.CONTAINER
        ) {
            to.children.add(
                block.id
            )

            block.parent = to
        }

        debugBlockTree()
        onDataChanged()
    }

    private fun getScriptName(): String {
        return "TODO.json"
    }

    // ------- Action Menu listener -------

    public fun onImportClipboard() {
        val clipboard =
            activity.getSystemService(
                Context.CLIPBOARD_SERVICE
            ) as android.content.ClipboardManager

        if (!clipboard.hasPrimaryClip()) {
            Toast.makeText(
                activity,
                getString(
                    R.string.sim_custom_serial_script_import_clipboard_empty
                ),
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        val clip =
            clipboard.primaryClip
                ?: return

        if (clip.itemCount == 0) {
            Toast.makeText(
                activity,
                getString(
                    R.string.sim_custom_serial_script_import_clipboard_empty
                ),
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        val text =
            clip.getItemAt(0)
                .coerceToText(activity)
                .toString()

        if (text.isBlank()) {
            Toast.makeText(
                activity,
                getString(
                    R.string.sim_custom_serial_script_import_clipboard_empty
                ),
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        try {
            val json =
                JSONObject(text)

            fromJson(json)

            Toast.makeText(
                activity,
                getString(
                    R.string.sim_custom_serial_script_import_clipboard_success
                ),
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            logDebug(
                "Clipboard import failed: " +
                    e.message
            )

            Toast.makeText(
                activity,
                getString(
                    R.string.sim_custom_serial_script_import_clipboard_error,
                    e.message ?: ""
                ),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    public fun onExportClipboard() {
        val text =
            toJson().toString()

        val clipboard =
            activity.getSystemService(
                android.content.Context.CLIPBOARD_SERVICE
            ) as android.content.ClipboardManager

        val clip =
            android.content.ClipData.newPlainText(
                getScriptName(),
                text
            )

        clipboard.setPrimaryClip(clip)

        Toast.makeText(
            activity,
            getString(
                R.string.sim_custom_serial_script_export_clipboard_success
            ),
            Toast.LENGTH_SHORT
        ).show()
    }

    public fun onExportFile() {
        activity.fileExportPendingData =
            toJson().toString()

        val intent =
            Intent(
                Intent.ACTION_CREATE_DOCUMENT
            ).apply {
                addCategory(
                    Intent.CATEGORY_OPENABLE
                )

                type = "application/json"

                putExtra(
                    Intent.EXTRA_TITLE,
                    getScriptName()
                )
            }

        activity.fileExportLauncher.launch(
            intent
        )
    }

    public fun shareConfigAsText() {
        val text =
            toJson().toString()

        val intent =
            Intent(
                Intent.ACTION_SEND
            ).apply {
                type = "text/plain"

                putExtra(
                    Intent.EXTRA_SUBJECT,
                    getScriptName()
                )

                putExtra(
                    Intent.EXTRA_TEXT,
                    text
                )
            }

        activity.startActivity(
            Intent.createChooser(
                intent,
                getString(
                    R.string.sim_custom_serial_script_share_script_title
                )
            )
        )
    }

    public fun onAddDelay() {
        addBlock(
            BlockController.Type.DELAY
        )
    }

    public fun onAddRecv() {
        addBlock(
            BlockController.Type.RECV
        )
    }

    public fun onAddSend() {
        addBlock(
            BlockController.Type.SEND
        )
    }

    public fun onAddContainer() {
        addBlock(
            BlockController.Type.CONTAINER
        )
    }

    public fun onDelete() {
        val blocksToDelete =
            selectedBlocks
                .mapNotNull { blockId ->
                    blocks.find {
                        it.id == blockId
                    }
                }
                .toList()

        val linksToDelete =
            selectedLinks
                .mapNotNull { (from, to) ->
                    links.find {
                        it.from == from &&
                            it.to == to
                    }
                }
                .toList()

        for (block in blocksToDelete) {
            rmBlock(block)
        }

        for (link in linksToDelete) {
            rmLink(link)
        }

        onDataChanged()
    }

    // ------- End Action Menu listener -------

    fun isSomeSelection(): Boolean {
        return !selectedBlocks.isEmpty() ||
            !selectedLinks.isEmpty()
    }

    override fun toJson(): JSONObject {
        val root =
            JSONObject()

        root.put(
            "schema",
            SCHEMA
        )

        root.put(
            "version",
            VERSION
        )

        val content =
            JSONObject()

        root.put(
            "content",
            content
        )

        val jsonBlocks =
            JSONArray()

        for (block in blocks) {
            jsonBlocks.put(
                block.toJson()
            )
        }

        content.put(
            "block",
            jsonBlocks
        )

        val jsonFlow =
            JSONArray()

        for (link in links) {
            val linkObject =
                JSONObject()

            linkObject.put(
                "from",
                link.from
            )

            linkObject.put(
                "to",
                link.to
            )

            jsonFlow.put(
                linkObject
            )
        }

        content.put(
            "flow",
            jsonFlow
        )

        return root
    }

    override fun fromJson(
        desc: JSONObject,
        parseErrorHandler: ((String) -> Unit)?
    ) {
        val schema =
            desc.optString(
                "schema",
                ""
            )

        if (schema != SCHEMA) {
            parseErrorHandler?.invoke(
                "Unsupported script schema: $schema"
            )
            return
        }

        val version =
            desc.optDouble(
                "version",
                -VERSION
            )

        if (version != VERSION) {
            parseErrorHandler?.invoke(
                "Unsupported script version: $version"
            )
            return
        }

        val content =
            desc.optJSONObject(
                "content"
            )

        if (content == null) {
            parseErrorHandler?.invoke(
                "Missing script content"
            )
            return
        }

        val jsonBlocks =
            content.optJSONArray(
                "block"
            ) ?: JSONArray()

        val jsonFlow =
            content.optJSONArray(
                "flow"
            ) ?: JSONArray()

        /*
         * Build the models first. This allows container references
         * to refer to blocks appearing later in the JSON array.
         */
        val importedBlocks =
            mutableListOf<BlockController>()

        val blockIds =
            mutableSetOf<Int>()

        for (
            i in 0 until jsonBlocks.length()
        ) {
            val jsonBlock =
                jsonBlocks.getJSONObject(i)

            val id =
                jsonBlock.getInt("id")

            if (!blockIds.add(id)) {
                parseErrorHandler?.invoke(
                    "Duplicate block id: $id"
                )
                return
            }

            val block =
                BlockController.fromJson(
                    jsonBlock,
                    id,
                    parseErrorHandler
                )

            if (block != null) {
                importedBlocks.add(block)
            }
        }

        /*
         * Validate and rebuild parent relationships.
         */
        for (parent in importedBlocks) {
            for (childId in parent.children) {
                val child =
                    importedBlocks.find {
                        it.id == childId
                    }

                if (child == null) {
                    parseErrorHandler?.invoke(
                        "Block #$childId referenced by " +
                            "container #${parent.id} does not exist"
                    )
                    return
                }

                if (child === parent) {
                    parseErrorHandler?.invoke(
                        "Block #${parent.id} cannot contain itself"
                    )
                    return
                }

                if (
                    child.parent != null &&
                    child.parent !== parent
                ) {
                    parseErrorHandler?.invoke(
                        "Block #$childId has multiple parents"
                    )
                    return
                }

                child.parent = parent
            }
        }

        /*
         * Replace the current script.
         */
        clear()

        /*
         * Add blocks to the view.
         */
        for (block in importedBlocks) {
            val blockView =
                view.addBlock(
                    model = block
                )

            block.viewLink(
                blockView
            )
        }

        for (block in importedBlocks) {
            blocks.add(block)
        }

        /*
         * Restore exact saved coordinates.
         *
         * x/y are:
         *   - world coordinates for root blocks
         *   - parent-relative coordinates for children
         */
        for (
            i in 0 until jsonBlocks.length()
        ) {
            val jsonBlock =
                jsonBlocks.getJSONObject(i)

            val blockId =
                jsonBlock.getInt("id")

            val block =
                blocks.find {
                    it.id == blockId
                } ?: continue

            val jsonView =
                jsonBlock.optJSONObject(
                    "view"
                ) ?: continue

            block.view?.x =
                jsonView.optDouble(
                    "x",
                    block.view?.x?.toDouble()
                        ?: 0.0
                ).toFloat()

            block.view?.y =
                jsonView.optDouble(
                    "y",
                    block.view?.y?.toDouble()
                        ?: 0.0
                ).toFloat()
        }

        /*
         * Restore links.
         */
        for (
            i in 0 until jsonFlow.length()
        ) {
            val jsonLink =
                jsonFlow.getJSONObject(i)

            val from =
                jsonLink.getInt("from")

            val to =
                jsonLink.getInt("to")

            if (blocks.none { it.id == from }) {
                parseErrorHandler?.invoke(
                    "Link source block #$from does not exist"
                )
                return
            }

            if (blocks.none { it.id == to }) {
                parseErrorHandler?.invoke(
                    "Link destination block #$to does not exist"
                )
                return
            }

            val link =
                LinkController(
                    from = from,
                    to = to
                )

            val linkView =
                view.addLink(
                    model = link
                )

            link.viewLink(
                linkView
            )

            links.add(link)
        }

        debugBlockTree()

        onDataChanged()
    }

    public fun dp(
        value: Int
    ): Int {
        return (
            value *
                resources.displayMetrics.density
            ).toInt()
    }

    private fun debugBlockTree() {
        if (!BuildConfig.DEBUG) {
            return
        }

        fun printBlock(
            block: BlockController,
            depth: Int
        ) {
            val indent =
                "  ".repeat(depth)

            val parentId =
                block.parent
                    ?.id
                    ?.toString()
                    ?: "null"

            val childrenIds =
                if (block.children.isEmpty()) {
                    "[]"
                } else {
                    block.children.joinToString(
                        prefix = "[",
                        postfix = "]"
                    )
                }

            logDebug(
                "${indent}Block #${block.id} " +
                    "type=${block.type} " +
                    "parent=$parentId " +
                    "children=$childrenIds"
            )

            for (childId in block.children) {
                val child =
                    blocks.find {
                        it.id == childId
                    }

                if (child != null) {
                    printBlock(
                        child,
                        depth + 1
                    )
                } else {
                    logDebug(
                        "${indent}  MISSING CHILD #$childId"
                    )
                }
            }
        }

        logDebug(
            "========== BLOCK TREE =========="
        )

        val roots =
            blocks.filter {
                it.parent == null
            }

        for (root in roots) {
            printBlock(
                root,
                0
            )
        }

        logDebug(
            "========== BLOCKS NOT REACHED =========="
        )

        val reached =
            mutableSetOf<Int>()

        fun collect(
            block: BlockController
        ) {
            if (!reached.add(block.id)) {
                return
            }

            for (childId in block.children) {
                blocks.find {
                    it.id == childId
                }?.let {
                    collect(it)
                }
            }
        }

        for (root in roots) {
            collect(root)
        }

        for (block in blocks) {
            if (!reached.contains(block.id)) {
                logDebug(
                    "UNREACHED Block #${block.id} " +
                        "type=${block.type} " +
                        "parent=${block.parent?.id ?: "null"} " +
                        "children=${block.children}"
                )
            }
        }

        logDebug(
            "================================"
        )
    }
}