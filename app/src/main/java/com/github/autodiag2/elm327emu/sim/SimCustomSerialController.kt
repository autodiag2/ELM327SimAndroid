package com.github.autodiag2.elm327emu.sim

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
import com.github.autodiag2.elm327emu.sim.SimCustomSerialView
import android.util.Log
import com.github.autodiag2.elm327emu.BuildConfig

class SimCustomSerialController(
    context: Context
) : LinearLayout(context), SimCustomSerialView.Listener {

    public var view: SimCustomSerialView
    
    open class ElementModel<V>(
        var view: V? = null,
        val id: Int = id_track++
    ) {
        companion object {
            private var id_track: Int = 1
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
        view: SimCustomSerialView.Block? = null,
        val children: MutableList<Int> = mutableListOf(),
        var parent: Block? = null
    ) : ElementModel<SimCustomSerialView.Block>(view) {
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
        view: SimCustomSerialView.Link? = null
    ) : ElementModel<SimCustomSerialView.Link>(view)

    public val blocks = mutableListOf<Block>()
    public val links = mutableListOf<Link>()
    public var selectedBlock: Block? = null
    public var selectedLink: Link? = null

    init {
        orientation = VERTICAL

        LayoutInflater.from(context).inflate(
            R.layout.sim_custom_serial_screen,
            this,
            true
        )

        view = findViewById(R.id.custom_serial_view)
        view.model = this
    }

    // ------------ Listeners ------------
    override fun onBlockClicked(
        node: SimCustomSerialView.Block
    ) {
        blocks.find { it.id == node.model!!.id }?.let {
            editBlock(it)
        }
    }

    override fun onBlockLongClicked(
        node: SimCustomSerialView.Block
    ) {
        // TODO
    }

    override fun onCreateLink(
        from: SimCustomSerialView.Block
    ) {
        // TODO
    }

    override fun onLinkToBlock(
        from: SimCustomSerialView.Block,
        to: SimCustomSerialView.Block
    ) {
        val linkModel = Link(from.model!!.id, to.model!!.id)
        val linkView = view.addLink(model = linkModel)
        linkModel.view = linkView
        links.add(linkModel)
    }

    override fun onBlockIncluded(
        parent: SimCustomSerialView.Block,
        child: SimCustomSerialView.Block
    ) {
        assert(parent != child)
        if (! parent.model!!.children.contains(child.model!!.id)) {
            parent.model!!.children.add(child.model!!.id)
        }
        child.model!!.parent = parent.model!!
        debugBlockTree()
    }

    override fun onBlockExcluded(
        parent: SimCustomSerialView.Block,
        child: SimCustomSerialView.Block
    ) {
        assert(parent != child)
        parent.model!!.children.remove(child.model!!.id)
        child.model!!.parent = null
    }

    override fun onElementSelected(view: ElementView<*>) {
        val element_model = view.model
        if ( element_model is Block ) {
            selectedBlock = element_model
        } else if ( element_model is Link ) {
            selectedLink = element_model
        } else {
            assert(false)
        }
    }

    override fun onElementUnselected(view: ElementView<*>) {
        val element_model = view.model
        if ( element_model is Block ) {
            selectedBlock = null
        } else if ( element_model is Link ) {
            selectedLink = null
        } else {
            assert(false)
        }
    }
    
    override fun onUnselectAll() {
        selectedBlock = null
        selectedLink = null
    }
    // ------------ End Listeners ------------

    public fun logDebug(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d("SimCustomSerial", message)
        }
    }

    fun getString(resId: Int, vararg formatArgs: Any?): String {
        return context.getString(resId, *formatArgs.map { it ?: "" }.toTypedArray())
    }

    public fun clear() {
        android.app.AlertDialog.Builder(context)
            .setTitle(R.string.sim_custom_serial_script_clear_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                blocks.clear()
                links.clear()
                onUnselectAll()
                view.refresh()
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

        if (selectedBlock == blockm) {
            selectedBlock = null
        }

        selectedLink =
            selectedLink?.takeUnless {
                it.from == blockm.id ||
                it.to == blockm.id
            }

        links.removeAll {
            it.from == blockm.id ||
            it.to == blockm.id
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
        selectedBlock?.let {
            rmBlock(it)
        }
        selectedLink?.let {
            rmLink(it)
        }
        onUnselectAll()
        view.refresh()
    }
    
    private fun editDelay(block: Block) {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(block.delay.toString())
        }

        android.app.AlertDialog.Builder(context)
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
        val layout = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(16))
        }

        val mode = Spinner(context)

        mode.adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_item,
            listOf(
                "Exact",
                "Regular expression"
            )
        )

        mode.setSelection(
            if (block.match == "regex") 1 else 0
        )

        val initial_text = EditText(context).apply {
            hint = "Pattern"
            setText(block.text)
            inputType =
                InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }

        val escapes = CheckBox(context).apply {
            text = "Interpret \\r, \\n, \\xhh..."
            isChecked = block.interpretEscapes
        }

        layout.addView(mode)
        layout.addView(initial_text)
        layout.addView(escapes)

        android.app.AlertDialog.Builder(context)
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
        val layout = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(16))
        }

        val initial_text = EditText(context).apply {
            hint = "ASCII text"
            setText(block.text)
            inputType =
                InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }

        val eol = CheckBox(context).apply {
            text = "Automatically append EOL"
            isChecked = block.includeEol
        }

        val escapes = CheckBox(context).apply {
            text = "Interpret \\r, \\n, \\xhh..."
            isChecked = block.interpretEscapes
        }

        layout.addView(initial_text)
        layout.addView(eol)
        layout.addView(escapes)

        android.app.AlertDialog.Builder(context)
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
        val input = EditText(context).apply {
            setText(block.name)
        }

        android.app.AlertDialog.Builder(context)
            .setTitle(R.string.sim_custom_serial_script_container)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                block.name = input.text.toString()
                view.refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun isBlockSelected(block: Any?): Boolean {
        if ( block == null ) {
            return false
        } else if ( block is Int ) {
            return isBlockSelected(blocks.find { it.id == block })
        } else {
            return selectedBlock != null && selectedBlock == block
        }
    }

    fun isSomeSelection(): Boolean {
        return selectedBlock != null || selectedLink != null
    }

    fun toJson(): JSONObject {
        val root = JSONObject()

        root.put(
            "schema",
            "autodiag/sim/elm327/serialscript"
        )

        root.put(
            "version",
            1.0
        )

        val content = JSONObject()
        root.put("content", content)

        val jsonBlocks = JSONArray()

        for (block in blocks) {
            val jsonBlock = JSONObject()

            jsonBlock.put("id", block.id)

            when (block.type) {
                Block.Type.DELAY -> {
                    jsonBlock.put("type", "delay")
                    jsonBlock.put("content", block.delay)
                }

                Block.Type.RECV -> {
                    jsonBlock.put("type", "recv")

                    val value = JSONObject()

                    value.put("match", block.match)
                    value.put("text", block.text)
                    value.put(
                        "interpret_esc",
                        block.interpretEscapes
                    )

                    jsonBlock.put("content", value)
                }

                Block.Type.SEND -> {
                    jsonBlock.put("type", "send")

                    val value = JSONObject()

                    value.put("text", block.text)
                    value.put(
                        "include_eol",
                        block.includeEol
                    )
                    value.put(
                        "interpret_esc",
                        block.interpretEscapes
                    )

                    jsonBlock.put("content", value)
                }

                Block.Type.CONTAINER -> {
                    jsonBlock.put("type", "container")

                    val value = JSONObject()

                    value.put("name", block.name)

                    val children = JSONArray()

                    for (child in block.children) {
                        children.put(child)
                    }

                    value.put("blocks", children)

                    jsonBlock.put("content", value)
                }
            }

            jsonBlocks.put(jsonBlock)
        }

        content.put("block", jsonBlocks)

        val jsonFlow = JSONArray()

        for (link in links) {
            val linkObject = JSONObject()

            linkObject.put("from", link.from)
            linkObject.put("to", link.to)

            jsonFlow.put(linkObject)
        }

        content.put("flow", jsonFlow)

        return root
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