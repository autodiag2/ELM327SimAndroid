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
import com.github.autodiag2.elm327emu.sim.serial.CustomView
import android.util.Log
import com.github.autodiag2.elm327emu.BuildConfig
import com.github.autodiag2.elm327emu.ui.JsonConfigurable
import com.github.autodiag2.elm327emu.MainActivity
import android.content.Intent
import com.github.autodiag2.elm327emu.LogLevel
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import com.github.autodiag2.elm327emu.sim.EmuInterface

const val SCHEMA = "autodiag/sim/elm327/serialscript"
const val VERSION = 1.0

class CustomController(
    private val activity: MainActivity
) : LinearLayout(activity), CustomView.Listener, JsonConfigurable {

    public var view: CustomView
    private val stateMachine = StateMachine(this)

    open class ElementModel<V>(
        var view: V? = null,
        var id: Int = gen_id_track()
    ) {
        companion object {
            private var id_track: Int = 1
            public fun gen_id_track(): Int {
                return id_track++
            }
        }

        fun viewLink(view_arg: V) {
            view = view_arg
        }
    }

    open class ElementView<M>(
        var model: M? = null
    ) {
        fun modelLink(model_arg: M) {
            model = model_arg
        }
    }

    open class Block(
        var type: Block.Type,
        var delay: Int = 0,
        var text: String = "",
        var match: String = "exact",
        var includeEol: Boolean = false,
        var interpretEscapes: Boolean = true,
        var name: String = "",
        view: CustomView.Block? = null,
        val children: MutableList<Int> = mutableListOf(),
        var parent: Block? = null,
        id: Int = ElementModel.gen_id_track()
    ) : ElementModel<CustomView.Block>(
        view = view,
        id = id
    ) {

        enum class Type {
            DELAY,
            RECV,
            SEND,
            CONTAINER
        }
    }

    open class Link(
        val from: Int,
        val to: Int,
        view: CustomView.Link? = null
    ) : ElementModel<CustomView.Link>(view)

    public val blocks = mutableListOf<Block>()
    public val links = mutableListOf<Link>()
    val selectedBlocks = mutableSetOf<Int>()
    val selectedLinks = mutableSetOf<Pair<Int, Int>>()

    public lateinit var emuInput: PipedOutputStream
    public lateinit var emuOutput: PipedInputStream

    init {
        orientation = VERTICAL

        LayoutInflater.from(activity).inflate(
            R.layout.sim_custom_serial_screen,
            this,
            true
        )

        view = findViewById(R.id.custom_serial_view)
        view.model = this
    }

    // ------------ Listeners ------------
    override fun onBlockClicked(
        node: CustomView.Block
    ) {
        blocks.find { it.id == node.model!!.id }?.let {
            editBlock(it)
        }
    }

    override fun onLinkToBlock(
        from: CustomView.Block,
        to: CustomView.Block
    ) {
        val linkModel = Link(from.model!!.id, to.model!!.id)
        val linkView = view.addLink(model = linkModel)
        linkModel.view = linkView
        links.add(linkModel)
    }

    override fun onBlockIncluded(
        parent: CustomView.Block,
        child: CustomView.Block
    ) {
        assert(parent != child)
        if (! parent.model!!.children.contains(child.model!!.id)) {
            parent.model!!.children.add(child.model!!.id)
        }
        child.model!!.parent = parent.model!!
        debugBlockTree()
    }

    override fun onBlockExcluded(
        parent: CustomView.Block,
        child: CustomView.Block
    ) {
        assert(parent != child)
        parent.model!!.children.remove(child.model!!.id)
        child.model!!.parent = null
    }

    override fun onElementSelected(element: ElementView<*>) {
        when (element) {
            is CustomView.Block -> {
                selectedBlocks.add(element.model!!.id)
            }

            is CustomView.Link -> {
                val link = element.model!!
                selectedLinks.add(
                    Pair(link.from, link.to)
                )
            }
        }
    }

    override fun onElementUnselected(
        element: CustomController.ElementView<*>
    ) {
        when (element) {
            is CustomView.Block -> {
                selectedBlocks.remove(element.model!!.id)
            }

            is CustomView.Link -> {
                val link = element.model!!
                selectedLinks.remove(
                    Pair(link.from, link.to)
                )
            }
        }
    }
    
    override fun onUnselectAll() {
        selectedBlocks.clear()
        selectedLinks.clear()
    }

    // ------------ End Listeners ------------

    // ------------ StateMachine ------------
    private val stateHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val stateRunnable =
        object : Runnable {
            override fun run() {
                stateMachine.process()

                if (stateMachine.isRunning()) {
                    stateHandler.postDelayed(this, 10L)
                }
            }
        }

    fun startScript() {
        val emu = activity.bridgeOrchestrator as EmuInterface
        val inputPipe = PipedInputStream()
        emuInput = PipedOutputStream(inputPipe)
        val input = inputPipe

        val outputPipe = PipedInputStream()
        emuOutput = outputPipe
        val output = PipedOutputStream(outputPipe)

        emu.emuHookStreams(input, output)
        stateMachine.start()
        stateHandler.post(stateRunnable)
    }

    fun stopScript() {
        val emu = activity.bridgeOrchestrator as EmuInterface
        stateMachine.stop()
        stateHandler.removeCallbacks(stateRunnable)
        emu.emuUnHookStreams()
    }
    // ------------ End StateMachine ------------

    fun onRunStateChange(state: Boolean) {
        if ( state ) {
            startScript()
        } else {
            stopScript()
        }
    }

    fun isBlockSelected(block: Any?): Boolean {
        val blockId = when (block) {
            is Int -> block

            is Block ->
                block.id

            null ->
                return false

            else ->
                return false
        }

        return selectedBlocks.contains(blockId)
    }

    fun isLinkSelected(link: Any?): Boolean {
        val linkKey = when (link) {
            is Pair<*, *> -> {
                val from = link.first as? Int ?: return false
                val to = link.second as? Int ?: return false

                Pair(from, to)
            }

            is Int -> {
                val linkModel =
                    links.find { it.id == link }
                        ?: return false

                Pair(
                    linkModel.from,
                    linkModel.to
                )
            }

            is Link ->
                Pair(
                    link.from,
                    link.to
                )

            null ->
                return false

            else ->
                return false
        }

        return selectedLinks.contains(linkKey)
    }

    fun toggleBlockSelection(block: Block) {
        if (!selectedBlocks.add(block.id)) {
            selectedBlocks.remove(block.id)
        }

        view.refresh()
    }

    fun toggleLinkSelection(link: Link) {
        val key = Pair(link.from, link.to)

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

    public fun logDebug(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d("SimCustomSerial", message)
        }
    }

    fun getString(resId: Int, vararg formatArgs: Any?): String {
        return activity.getString(resId, *formatArgs.map { it ?: "" }.toTypedArray())
    }

    public fun clear() {
        blocks.clear()
        links.clear()
        onUnselectAll()
        view.refresh()
    }

    public fun clearWithDialog() {
        android.app.AlertDialog.Builder(activity)
            .setTitle(R.string.sim_custom_serial_script_clear_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                clear()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun rmLink(link: Any) {
        var linko = link
        if ( link is Int ) {
            if ( 0 < link ) {
                linko = links.find { it.id == link } as Link
            }
        }
        assert(linko is Link)
        val linkm = linko as Link
        selectedLinks.remove(
            Pair(
                linkm.from,
                linkm.to
            )
        )
        links.remove(linkm)
        view.refresh()
    }

    private fun rmBlock(block: Any) {
        var blocko = block

        if (block is Int) {
            if (0 < block) {
                blocko = blocks.find { it.id == block } as Block
            }
        }

        assert(blocko is Block)

        val blockm = blocko as Block

        for (childblock in blockm.children.toList()) {
            rmBlock(childblock)
        }
        if ( blockm.parent != null ) {
            blockm.parent!!.children.removeAll {
                it == blockm.id
            }
            blockm.parent = null
        }

        blocks.remove(blockm)

        if ( isBlockSelected(blockm) ) {
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

        view.refresh()
    }

    private fun addBlock(
        type: Block.Type,
        to: Block? = null,
        name: String = ""
    ) {
        var blockName = name
        if ( name.isEmpty() ) {
            blockName = when (type) {
                Block.Type.DELAY -> getString(R.string.sim_custom_serial_script_block_name_delay)
                Block.Type.RECV -> getString(R.string.sim_custom_serial_script_block_name_recv)
                Block.Type.SEND -> getString(R.string.sim_custom_serial_script_block_name_send)
                Block.Type.CONTAINER -> getString(R.string.sim_custom_serial_script_block_name_container)
            }
        }
        val block = Block(
            type = type,
            name = blockName
        )
        val blockView = view.addBlock(model = block)
        block.viewLink(blockView)
        blocks.add(block)
        if (to?.type == Block.Type.CONTAINER) {
            to.children.add(block.id)
            block.parent = to
        }
        view.refresh()
        debugBlockTree()
    }

    private fun blockTitle(block: Block): String {
        return when (block.type) {
            Block.Type.DELAY ->
                "#${block.id}  Delay ${block.delay} ms"

            Block.Type.RECV ->
                "#${block.id}  Receive: ${block.text}"

            Block.Type.SEND ->
                "#${block.id}  Send: ${block.text}"

            Block.Type.CONTAINER ->
                "#${block.id}  ${block.name}"
        }
    }

    private fun editBlock(block: Block) {
        when (block.type) {
            Block.Type.DELAY -> editDelay(block)
            Block.Type.RECV -> editReceive(block)
            Block.Type.SEND -> editSend(block)
            Block.Type.CONTAINER -> editContainer(block)
        }
    }

    private fun getScriptName(): String {
        return "TODO.json"
    }

    // ------- Action Menu listerner -------
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
                "Clipboard import failed: ${e.message}"
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
        val text = toJson().toString()

        val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager

        val clip = android.content.ClipData.newPlainText(getScriptName(), text)
        clipboard.setPrimaryClip(clip)

        Toast.makeText(activity,
            getString(R.string.sim_custom_serial_script_export_clipboard_success),
            Toast.LENGTH_SHORT
        ).show()
    }
    public fun onExportFile() {
        activity.fileExportPendingData = toJson().toString()

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, getScriptName())
        }

        activity.fileExportLauncher.launch(intent)
    }
    public fun shareConfigAsText() {
        val text = toJson().toString()

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getScriptName())
            putExtra(Intent.EXTRA_TEXT, text)
        }

        activity.startActivity(
            Intent.createChooser(intent, getString(R.string.sim_custom_serial_script_share_script_title))
        )
    }
    public fun onAddDelay() {
        addBlock(Block.Type.DELAY)
    }
    public fun onAddRecv() {
        addBlock(Block.Type.RECV)
    }
    public fun onAddSend() {
        addBlock(Block.Type.SEND)
    }
    public fun onAddContainer() {
        addBlock(Block.Type.CONTAINER)
    }
    public fun onDelete() {
        val blocksToDelete =
            selectedBlocks
                .mapNotNull { blockId ->
                    blocks.find { it.id == blockId }
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

        view.refresh()
    }
    // ------- End Action Menu listerner -------
    
    private fun editDelay(block: Block) {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(block.delay.toString())
        }

        android.app.AlertDialog.Builder(activity)
            .setTitle(R.string.sim_custom_serial_script_delay)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                block.delay = input.text.toString().toIntOrNull() ?: 0
                view.refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun editReceive(block: Block) {
        val layout = LinearLayout(activity).apply {
            orientation = VERTICAL
            setPadding(dp(16))
        }

        val mode = Spinner(activity)

        mode.adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_item,
            listOf(
                "Exact",
                "Regular expression"
            )
        )

        mode.setSelection(
            if (block.match == "regex") 1 else 0
        )

        val initial_text = EditText(activity).apply {
            hint = "Pattern"
            setText(block.text)
            inputType =
                InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }

        val escapes = CheckBox(activity).apply {
            text = "Interpret \\r, \\n, \\xhh..."
            isChecked = block.interpretEscapes
        }

        layout.addView(mode)
        layout.addView(initial_text)
        layout.addView(escapes)

        android.app.AlertDialog.Builder(activity)
            .setTitle(R.string.sim_custom_serial_script_receive)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                block.match =
                    if (mode.selectedItemPosition == 1) {
                        "regex"
                    } else {
                        "exact"
                    }

                block.text = initial_text.text.toString()
                block.interpretEscapes = escapes.isChecked

                view.refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun editSend(block: Block) {
        val layout = LinearLayout(activity).apply {
            orientation = VERTICAL
            setPadding(dp(16))
        }

        val initial_text = EditText(activity).apply {
            hint = "ASCII text"
            setText(block.text)
            inputType =
                InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }

        val eol = CheckBox(activity).apply {
            text = "Automatically append EOL"
            isChecked = block.includeEol
        }

        val escapes = CheckBox(activity).apply {
            text = "Interpret \\r, \\n, \\xhh..."
            isChecked = block.interpretEscapes
        }

        layout.addView(initial_text)
        layout.addView(eol)
        layout.addView(escapes)

        android.app.AlertDialog.Builder(activity)
            .setTitle(R.string.sim_custom_serial_script_send)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                block.text = initial_text.text.toString()
                block.includeEol = eol.isChecked
                block.interpretEscapes = escapes.isChecked
                view.refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun editContainer(block: Block) {
        val input = EditText(activity).apply {
            setText(block.name)
        }

        android.app.AlertDialog.Builder(activity)
            .setTitle(R.string.sim_custom_serial_script_container)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                block.name = input.text.toString()
                view.refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun isSomeSelection(): Boolean {
        return !selectedBlocks.isEmpty() || !selectedLinks.isEmpty()
    }

    override fun toJson(): JSONObject {
        val root = JSONObject()

        root.put(
            "schema",
            SCHEMA
        )

        root.put(
            "version",
            VERSION
        )

        val content = JSONObject()

        root.put(
            "content",
            content
        )

        val jsonBlocks = JSONArray()

        for (block in blocks) {
            val jsonBlock = JSONObject()

            jsonBlock.put(
                "id",
                block.id
            )

            when (block.type) {
                Block.Type.DELAY -> {
                    jsonBlock.put(
                        "type",
                        "delay"
                    )

                    val value = JSONObject()
                    value.put(
                        "name",
                        block.name
                    )
                    value.put(
                        "delay",
                        block.delay
                    )
                    jsonBlock.put(
                        "content",
                        value
                    )
                }

                Block.Type.RECV -> {
                    jsonBlock.put(
                        "type",
                        "recv"
                    )

                    val value = JSONObject()

                    value.put(
                        "name",
                        block.name
                    )

                    value.put(
                        "match",
                        block.match
                    )

                    value.put(
                        "text",
                        block.text
                    )

                    value.put(
                        "interpret_esc",
                        block.interpretEscapes
                    )

                    jsonBlock.put(
                        "content",
                        value
                    )
                }

                Block.Type.SEND -> {
                    jsonBlock.put(
                        "type",
                        "send"
                    )

                    val value = JSONObject()

                    value.put(
                        "name",
                        block.name
                    )

                    value.put(
                        "text",
                        block.text
                    )

                    value.put(
                        "include_eol",
                        block.includeEol
                    )

                    value.put(
                        "interpret_esc",
                        block.interpretEscapes
                    )

                    jsonBlock.put(
                        "content",
                        value
                    )
                }

                Block.Type.CONTAINER -> {
                    jsonBlock.put(
                        "type",
                        "container"
                    )

                    val value = JSONObject()

                    value.put(
                        "name",
                        block.name
                    )

                    val children = JSONArray()

                    for (child in block.children) {
                        children.put(child)
                    }

                    value.put(
                        "blocks",
                        children
                    )

                    jsonBlock.put(
                        "content",
                        value
                    )
                }
            }

            /*
            * View position.
            *
            * x/y remain in the block's current coordinate system:
            * root blocks use world coordinates,
            * child blocks use coordinates relative to their container.
            */
            val blockView = block.view

            if (blockView != null) {
                val view = JSONObject()

                view.put(
                    "x",
                    blockView.x
                )

                view.put(
                    "y",
                    blockView.y
                )

                jsonBlock.put(
                    "view",
                    view
                )
            }

            jsonBlocks.put(
                jsonBlock
            )
        }

        content.put(
            "block",
            jsonBlocks
        )

        val jsonFlow = JSONArray()

        for (link in links) {
            val linkObject = JSONObject()

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

    override fun fromJson(desc: JSONObject, parseErrorHandler: ((String) -> Unit)?) {
        val schema = desc.optString("schema", "")

        if (schema != SCHEMA) {
            parseErrorHandler?.invoke(
                "Unsupported script schema: $schema"
            )
            return
        }

        val version = desc.optDouble("version", -VERSION)

        if (version != VERSION) {
            parseErrorHandler?.invoke(
                "Unsupported script version: $version"
            )
            return
        }

        val content = desc.optJSONObject("content")

        if (content == null) {
            parseErrorHandler?.invoke("Missing script content")
            return
        }

        val jsonBlocks =
            content.optJSONArray("block")
                ?: JSONArray()

        val jsonFlow =
            content.optJSONArray("flow")
                ?: JSONArray()

        /*
        * Build the models first. This allows container references
        * to refer to blocks appearing later in the JSON array.
        */
        val importedBlocks = mutableListOf<Block>()
        val blockIds = mutableSetOf<Int>()

        for (i in 0 until jsonBlocks.length()) {
            val jsonBlock = jsonBlocks.getJSONObject(i)

            val id = jsonBlock.getInt("id")

            if (!blockIds.add(id)) {
                parseErrorHandler?.invoke(
                    "Duplicate block id: $id"
                )
                return
            }

            val typeString =
                jsonBlock.getString("type")

            val type =
                when (typeString) {
                    "delay" ->
                        Block.Type.DELAY

                    "recv" ->
                        Block.Type.RECV

                    "send" ->
                        Block.Type.SEND

                    "container" ->
                        Block.Type.CONTAINER

                    else -> {
                        parseErrorHandler?.invoke(
                            "Unknown block type: $typeString"
                        )
                        return
                    }
                }

            val blockContent = jsonBlock.getJSONObject("content")
            val block =
                when (type) {
                    Block.Type.DELAY -> {
                        Block(
                            type = type,
                            delay = blockContent.optInt("delay", 10),
                            name = blockContent.optString("name", "Delay"),
                            id = id,
                        )
                    }

                    Block.Type.RECV -> {
                        Block(
                            type = type,
                            name = blockContent.optString("name", "Recv"),
                            match = blockContent.optString(
                                "match",
                                "exact"
                            ),
                            text = blockContent.optString(
                                "text",
                                ""
                            ),
                            interpretEscapes =
                                blockContent.optBoolean(
                                    "interpret_esc",
                                    true
                                ),
                            id = id
                        )
                    }

                    Block.Type.SEND -> {
                        Block(
                            type = type,
                            name = blockContent.optString("name", "Send"),
                            text = blockContent.optString(
                                "text",
                                ""
                            ),
                            includeEol =
                                blockContent.optBoolean(
                                    "include_eol",
                                    false
                                ),
                            interpretEscapes =
                                blockContent.optBoolean(
                                    "interpret_esc",
                                    true
                                ),
                            id = id
                        )
                    }

                    Block.Type.CONTAINER -> {
                        val children =
                            mutableListOf<Int>()

                        val jsonChildren =
                            blockContent.optJSONArray("blocks")

                        if (jsonChildren != null) {
                            for (j in 0 until jsonChildren.length()) {
                                children.add(
                                    jsonChildren.getInt(j)
                                )
                            }
                        }

                        Block(
                            type = type,
                            name = blockContent.optString(
                                "name",
                                ""
                            ),
                            children = children,
                            id = id
                        )
                    }
                }


            importedBlocks.add(block)

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
                if ( child == null ) {
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

                if (child.parent != null &&
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
                view.addBlock(model = block)
            block.viewLink(blockView)
        }
        for(block in importedBlocks) {
            blocks.add(block)
        }
        /*
        * Restore exact saved coordinates.
        *
        * x/y are:
        *   - world coordinates for root blocks
        *   - parent-relative coordinates for children
        */
        for (i in 0 until jsonBlocks.length()) {
            val jsonBlock =
                jsonBlocks.getJSONObject(i)

            val blockId =
                jsonBlock.getInt("id")

            val block =
                blocks.find {
                    it.id == blockId
                }
                    ?: continue

            val jsonView =
                jsonBlock.optJSONObject("view")
                    ?: continue

            block.view?.x =
                jsonView.optDouble(
                    "x",
                    block.view?.x?.toDouble() ?: 0.0
                ).toFloat()

            block.view?.y =
                jsonView.optDouble(
                    "y",
                    block.view?.y?.toDouble() ?: 0.0
                ).toFloat()
        }

        /*
        * Restore links.
        */
        for (i in 0 until jsonFlow.length()) {
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
                Link(
                    from = from,
                    to = to
                )

            val linkView =
                view.addLink(model = link)

            link.viewLink(linkView)

            links.add(link)
        }

        view.refresh()

        debugBlockTree()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun debugBlockTree() {
        if ( ! BuildConfig.DEBUG ) {
            return
        }
        fun printBlock(
            block: Block,
            depth: Int
        ) {
            val indent = "  ".repeat(depth)

            val parentId =
                block.parent?.id?.toString() ?: "null"

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

        logDebug("========== BLOCK TREE ==========")

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

        logDebug("========== BLOCKS NOT REACHED ==========")

        val reached = mutableSetOf<Int>()

        fun collect(block: Block) {
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

        logDebug("================================")
    }

}