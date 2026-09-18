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
import com.github.autodiag2.elm327emu.sim.CustomSerialEditorView

class SimCustomSerialScreen(
    context: Context
) : LinearLayout(context) {

    public var editor: CustomSerialEditorView

    enum class BlockType {
        DELAY,
        RECV,
        SEND,
        CONTAINER
    }

    data class Block(
        val id: Int,
        var type: BlockType,
        var delay: Int = 0,
        var text: String = "",
        var match: String = "exact",
        var includeEol: Boolean = false,
        var interpretEscapes: Boolean = true,
        var name: String = "",
        val children: MutableList<Int> = mutableListOf()
    )

    data class Link(
        val from: Int,
        val to: Int
    )

    private val blocks = mutableListOf<Block>()
    private val links = mutableListOf<Link>()

    private var nextId = 0


    init {
        orientation = VERTICAL

        LayoutInflater.from(context).inflate(
            R.layout.sim_custom_serial_screen,
            this,
            true
        )

        editor = findViewById(R.id.custom_serial_editor)

        addBlock(BlockType.CONTAINER, "Main")

        editor.listener = object : CustomSerialEditorView.Listener {

            override fun onNodeClicked(
                node: CustomSerialEditorView.Node
            ) {
                blocks.find { it.id == node.id }?.let {
                    editBlock(it)
                }
            }

            override fun onNodeLongClicked(
                node: CustomSerialEditorView.Node
            ) {
                blocks.find { it.id == node.id }?.let {
                    linkBlock(it)
                }
            }

            override fun onCreateLink(
                from: CustomSerialEditorView.Node
            ) {
                blocks.find { it.id == from.id }?.let {
                    linkBlock(it)
                }
            }

            override fun onLinkToNode(
                from: CustomSerialEditorView.Node,
                to: CustomSerialEditorView.Node
            ) {
                if (from.id == to.id) {
                    return
                }

                links.removeAll {
                    it.from == from.id &&
                    it.to == to.id
                }

                links.add(
                    Link(
                        from = from.id,
                        to = to.id
                    )
                )

                rebuild()
            }
        }
    }

    private fun updateEditor() {
        val nodes = blocks.mapIndexed { index, block ->
            CustomSerialEditorView.Node(
                id = block.id,
                type = block.type,
                title = blockTitle(block),
                x = 80f + (index % 2) * 360f,
                y = 80f + (index / 2) * 160f
            )
        }

        editor.setNodes(nodes)

        editor.setConnections(
            links.map {
                CustomSerialEditorView.Connection(
                    from = it.from,
                    to = it.to
                )
            }
        )
    }

    private fun addBlock(
        type: BlockType,
        name: String = ""
    ) {
        val block = Block(
            id = nextId++,
            type = type,
            name = name.ifEmpty {
                when (type) {
                    BlockType.DELAY -> "Delay"
                    BlockType.RECV -> "Receive"
                    BlockType.SEND -> "Send"
                    BlockType.CONTAINER -> "Container"
                }
            }
        )

        blocks.add(block)

        rebuild()
    }

    private fun rebuild() {
        val nodes = blocks.mapIndexed { index, block ->
            CustomSerialEditorView.Node(
                id = block.id,
                type = block.type,
                title = blockTitle(block),
                x = 80f + (index % 2) * 360f,
                y = 80f + (index / 2) * 160f
            )
        }

        editor.setNodes(nodes)

        editor.setConnections(
            links.map {
                CustomSerialEditorView.Connection(
                    from = it.from,
                    to = it.to
                )
            }
        )
    }

    private fun createBlockView(
        block: Block,
        indent: Int
    ): View {

        return View(context)
    }

    private fun blockTitle(block: Block): String {
        return when (block.type) {
            BlockType.DELAY ->
                "#${block.id}  Delay ${block.delay} ms"

            BlockType.RECV ->
                "#${block.id}  Receive: ${block.text}"

            BlockType.SEND ->
                "#${block.id}  Send: ${block.text}"

            BlockType.CONTAINER ->
                "#${block.id}  ${block.name}"
        }
    }

    private fun editBlock(block: Block) {
        when (block.type) {
            BlockType.DELAY -> editDelay(block)
            BlockType.RECV -> editReceive(block)
            BlockType.SEND -> editSend(block)
            BlockType.CONTAINER -> editContainer(block)
        }
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
                rebuild()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    public fun onAddDelay() {
        addBlock(BlockType.DELAY)
    }
    public fun onAddRecv() {
        addBlock(BlockType.RECV)
    }
    public fun onAddSend() {
        addBlock(BlockType.SEND)
    }
    public fun onAddContainer() {
        addBlock(BlockType.CONTAINER)
    }
    public fun onDelete() {
        editor.onDelete()
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

                rebuild()
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
                rebuild()
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
                rebuild()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun linkBlock(block: Block) {
        val candidates = blocks.filter {
            it.id != block.id
        }

        if (candidates.isEmpty()) {
            return
        }

        val labels = candidates.map {
            "${it.id}: ${blockTitle(it)}"
        }

        android.app.AlertDialog.Builder(context)
            .setTitle(R.string.sim_custom_serial_script_link)
            .setItems(labels.toTypedArray()) { _, index ->
                val target = candidates[index]

                links.removeAll {
                    it.from == block.id &&
                    it.to == target.id
                }

                links.add(
                    Link(
                        block.id,
                        target.id
                    )
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
                BlockType.DELAY -> {
                    jsonBlock.put("type", "delay")
                    jsonBlock.put("content", block.delay)
                }

                BlockType.RECV -> {
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

                BlockType.SEND -> {
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

                BlockType.CONTAINER -> {
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

    fun copyJson(): String {
        return toJson().toString(2)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}