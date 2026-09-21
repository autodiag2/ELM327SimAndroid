package com.github.autodiag2.elm327emu.sim.serial

import org.json.JSONArray
import org.json.JSONObject

// view imports
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.text.InputType
import com.github.autodiag2.elm327emu.R
import androidx.core.view.setPadding
// end view imports

open class BlockController(
    var type: BlockController.Type,
    var delay: Int = 0,
    var text: String = "",
    var match: String = "exact",
    var includeEol: Boolean = false,
    var interpretEscapes: Boolean = true,
    var name: String = "",
    view: BlockView? = null,
    val children: MutableList<Int> = mutableListOf(),
    var parent: BlockController? = null,
    id: Int? = null
) : ElementController<BlockView>(
    view = view,
    id = id
) {
    enum class Type {
        DELAY,
        RECV,
        SEND,
        CONTAINER
    }

    companion object {
        fun fromJson(jsonBlock: JSONObject, id: Int, parseErrorHandler: ((String) -> Unit)?): BlockController? {
            val typeString =
                jsonBlock.getString("type")

            val type =
                when (typeString) {
                    "delay" -> Type.DELAY
                    "recv" -> Type.RECV
                    "send" -> Type.SEND
                    "container" -> Type.CONTAINER
                    else -> {
                        parseErrorHandler?.invoke(
                            "Unknown block type: $typeString"
                        )
                        return null
                    }
                }

            val blockContent = jsonBlock.getJSONObject("content")
            return when (type) {
                    BlockController.Type.DELAY -> {
                        BlockController(
                            type = type,
                            delay = blockContent.optInt("delay", 10),
                            name = blockContent.optString("name", "Delay"),
                            id = id,
                        )
                    }

                    BlockController.Type.RECV -> {
                        BlockController(
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

                    BlockController.Type.SEND -> {
                        BlockController(
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

                    BlockController.Type.CONTAINER -> {
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

                        BlockController(
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
        }
    }
    fun viewRefresh() {
        view!!.parentView.refresh()
    }

    fun toJson(): JSONObject {
        val jsonBlock = JSONObject()

        jsonBlock.put(
            "id",
            id
        )

        when (type) {
            BlockController.Type.DELAY -> {
                jsonBlock.put(
                    "type",
                    "delay"
                )

                val value = JSONObject()
                value.put(
                    "name",
                    name
                )
                value.put(
                    "delay",
                    delay
                )
                jsonBlock.put(
                    "content",
                    value
                )
            }

            BlockController.Type.RECV -> {
                jsonBlock.put(
                    "type",
                    "recv"
                )

                val value = JSONObject()

                value.put(
                    "name",
                    name
                )

                value.put(
                    "match",
                    match
                )

                value.put(
                    "text",
                    text
                )

                value.put(
                    "interpret_esc",
                    interpretEscapes
                )

                jsonBlock.put(
                    "content",
                    value
                )
            }

            BlockController.Type.SEND -> {
                jsonBlock.put(
                    "type",
                    "send"
                )

                val value = JSONObject()

                value.put(
                    "name",
                    name
                )

                value.put(
                    "text",
                    text
                )

                value.put(
                    "include_eol",
                    includeEol
                )

                value.put(
                    "interpret_esc",
                    interpretEscapes
                )

                jsonBlock.put(
                    "content",
                    value
                )
            }

            BlockController.Type.CONTAINER -> {
                jsonBlock.put(
                    "type",
                    "container"
                )

                val value = JSONObject()

                value.put(
                    "name",
                    name
                )

                val childrenJson = JSONArray()

                for (child in children) {
                    childrenJson.put(child)
                }

                value.put(
                    "blocks",
                    childrenJson
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
        val blockView = view

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
        return jsonBlock
    }
    public fun edit() {
        when (type) {
            Type.DELAY -> editDelay()
            Type.RECV -> editReceive()
            Type.SEND -> editSend()
            Type.CONTAINER -> editContainer()
        }
    }

    private fun editDelay() {
        val input = EditText(view!!.context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(delay.toString())
        }

        android.app.AlertDialog.Builder(view!!.context)
            .setTitle(R.string.sim_custom_serial_script_delay)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                delay = input.text.toString().toIntOrNull() ?: 0
                viewRefresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun editReceive() {
        val layout = LinearLayout(view!!.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16))
        }

        val mode = Spinner(view!!.context)

        mode.adapter = ArrayAdapter(
            view!!.context,
            android.R.layout.simple_spinner_item,
            listOf(
                "Exact",
                "Regular expression"
            )
        )

        mode.setSelection(
            if (match == "regex") 1 else 0
        )

        val initial_text = EditText(view!!.context).apply {
            hint = "Pattern"
            setText(this@BlockController.text)
            inputType =
                InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }

        val escapes = CheckBox(view!!.context).apply {
            text = "Interpret \\r, \\n, \\xhh..."
            isChecked = interpretEscapes
        }

        layout.addView(mode)
        layout.addView(initial_text)
        layout.addView(escapes)

        android.app.AlertDialog.Builder(view!!.context)
            .setTitle(R.string.sim_custom_serial_script_receive)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                match =
                    if (mode.selectedItemPosition == 1) {
                        "regex"
                    } else {
                        "exact"
                    }

                text = initial_text.text.toString()
                interpretEscapes = escapes.isChecked

                viewRefresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun editSend() {
        val layout = LinearLayout(view!!.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16))
        }

        val initial_text = EditText(view!!.context).apply {
            hint = "ASCII text"
            setText(this@BlockController.text)
            inputType =
                InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }

        val eol = CheckBox(view!!.context).apply {
            text = "Automatically append EOL"
            isChecked = includeEol
        }

        val escapes = CheckBox(view!!.context).apply {
            text = "Interpret \\r, \\n, \\xhh..."
            isChecked = interpretEscapes
        }

        layout.addView(initial_text)
        layout.addView(eol)
        layout.addView(escapes)

        android.app.AlertDialog.Builder(view!!.context)
            .setTitle(R.string.sim_custom_serial_script_send)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                text = initial_text.text.toString()
                includeEol = eol.isChecked
                interpretEscapes = escapes.isChecked
                viewRefresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun editContainer() {
        val input = EditText(view!!.context).apply {
            setText(this@BlockController.name)
        }

        android.app.AlertDialog.Builder(view!!.context)
            .setTitle(R.string.sim_custom_serial_script_container)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                name = input.text.toString()
                viewRefresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // TEMP area
    fun dp(amount: Int): Int {
        return view!!.parentView!!.model!!.dp(amount)
    }
    // End TEMP area

}