package com.github.autodiag2.elm327emu.sim.serial

import org.json.JSONArray
import org.json.JSONObject
import com.github.autodiag2.elm327emu.BuildConfig
import android.util.Log

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
    var timeoutMs: Int = 0,
    var text: String = "",
    var match: String = "exact",
    var ignoreCase: Boolean = true,
    var includeEol: Boolean = false,
    var interpretEscapes: Boolean = true,
    var name: String = "",
    view: BlockView? = null,
    val children: MutableList<Int> = mutableListOf(),
    var parent: BlockController? = null,
    id: Int? = null,
    var state: State = State.IDLE
) : ElementController<BlockView>(
    view = view,
    id = id
) {
    enum class State {
        IDLE,
        IN_PROGRESS,
        SUCCESS,
        FAILED
    }

    enum class Type {
        DELAY,
        RECV,
        SEND,
        CONTAINER
    }

    companion object {
        fun parseEscapedBytes(
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

        fun fromJson(
            jsonBlock: JSONObject,
            id: Int,
            parseErrorHandler: ((String) -> Unit)?
        ): BlockController? {
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

            val blockContent =
                jsonBlock.getJSONObject("content")

            return when (type) {
                BlockController.Type.DELAY -> {
                    BlockController(
                        type = type,
                        timeoutMs =
                            blockContent.optInt(
                                "timeoutMs",
                                10
                            ),
                        name =
                            blockContent.optString(
                                "name",
                                "Delay"
                            ),
                        id = id
                    )
                }

                BlockController.Type.RECV -> {
                    BlockController(
                        type = type,
                        timeoutMs =
                            blockContent.optInt(
                                "timeoutMs",
                                0
                            ),
                        name =
                            blockContent.optString(
                                "name",
                                "Recv"
                            ),
                        match =
                            blockContent.optString(
                                "match",
                                "exact"
                            ),
                        ignoreCase = blockContent.optBoolean("ignoreCase", false),
                        text =
                            blockContent.optString(
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
                        timeoutMs =
                            blockContent.optInt(
                                "timeoutMs",
                                0
                            ),
                        name =
                            blockContent.optString(
                                "name",
                                "Send"
                            ),
                        text =
                            blockContent.optString(
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
                        blockContent.optJSONArray(
                            "blocks"
                        )

                    if (jsonChildren != null) {
                        for (
                            j in 0 until jsonChildren.length()
                        ) {
                            children.add(
                                jsonChildren.getInt(j)
                            )
                        }
                    }

                    BlockController(
                        type = type,
                        name =
                            blockContent.optString(
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

    fun resetState() {
        state = State.IDLE
    }

    fun onDataChanged() {
        view!!.parentView.model!!.onDataChanged()
    }

    fun viewRefresh() {
        view!!.parentView.refresh()
    }

    fun recvMatches(
        received: ByteArray
    ): Boolean {
        val expected =
            if (interpretEscapes) {
                parseEscapedBytes(text)
            } else {
                text.toByteArray(
                    Charsets.ISO_8859_1
                )
            }

        val result =
            when (match.lowercase()) {
                "exact" -> {
                    if (ignoreCase) {
                        received
                            .toString(Charsets.ISO_8859_1)
                            .equals(
                                expected.toString(
                                    Charsets.ISO_8859_1
                                ),
                                ignoreCase = true
                            )
                    } else {
                        received.contentEquals(
                            expected
                        )
                    }
                }

                "regex" -> {
                    val pattern =
                        if (interpretEscapes) {
                            parseEscapedBytes(text)
                                .toString(
                                    Charsets.ISO_8859_1
                                )
                        } else {
                            text
                        }

                    Regex(
                        pattern,
                        if (ignoreCase) {
                            setOf(RegexOption.IGNORE_CASE)
                        } else {
                            emptySet()
                        }
                    ).containsMatchIn(
                        received.toString(
                            Charsets.ISO_8859_1
                        )
                    )
                }

                else -> {
                    logDebug(
                        "Unknown match mode '${match}', " +
                            "using exact"
                    )

                    if (ignoreCase) {
                        received
                            .toString(Charsets.ISO_8859_1)
                            .equals(
                                expected.toString(
                                    Charsets.ISO_8859_1
                                ),
                                ignoreCase = true
                            )
                    } else {
                        received.contentEquals(
                            expected
                        )
                    }
                }
            }

        logDebug(
            "MATCH " +
                "${if (result) "SUCCESS" else "FAILED"} " +
                "block=${id} " +
                "mode=${match} " +
                "ignoreCase=${ignoreCase} " +
                "expected=${expected.toDebugString()} " +
                "received=${received.toDebugString()}"
        )

        return result
    }

    private fun ByteArray.toDebugString(): String {
        return buildString {
            for (byte in this@toDebugString) {
                val value =
                    byte.toInt() and 0xFF

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

    public fun logDebug(
        message: String
    ) {
        if (BuildConfig.DEBUG) {
            Log.d(
                "sim.serial.BlockController",
                message
            )
        }
    }

    fun toJson(): JSONObject {
        val jsonBlock =
            JSONObject()

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

                val value =
                    JSONObject()

                value.put(
                    "name",
                    name
                )

                value.put(
                    "timeoutMs",
                    timeoutMs
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

                val value =
                    JSONObject()

                value.put(
                    "name",
                    name
                )

                value.put(
                    "timeoutMs",
                    timeoutMs
                )

                value.put(
                    "match",
                    match
                )

                value.put(
                    "ignoreCase",
                    ignoreCase
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

                val value =
                    JSONObject()

                value.put(
                    "name",
                    name
                )

                value.put(
                    "timeoutMs",
                    timeoutMs
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

                val value =
                    JSONObject()

                value.put(
                    "name",
                    name
                )

                val childrenJson =
                    JSONArray()

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
        val blockView =
            view

        if (blockView != null) {
            val view =
                JSONObject()

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
            Type.DELAY ->
                editDelay()

            Type.RECV ->
                editReceive()

            Type.SEND ->
                editSend()

            Type.CONTAINER ->
                editContainer()
        }
    }

    private fun editDelay() {
        val input =
            EditText(view!!.context).apply {
                inputType =
                    InputType.TYPE_CLASS_NUMBER

                setText(
                    timeoutMs.toString()
                )
            }

        android.app.AlertDialog.Builder(
            view!!.context
        )
            .setTitle(
                R.string.sim_custom_serial_script_delay
            )
            .setView(input)
            .setPositiveButton(
                android.R.string.ok
            ) { _, _ ->
                timeoutMs =
                    input.text
                        .toString()
                        .toIntOrNull()
                        ?.coerceAtLeast(0)
                        ?: 0

                onDataChanged()
            }
            .setNegativeButton(
                android.R.string.cancel,
                null
            )
            .show()
    }

    private fun editReceive() {
        val layout =
            LinearLayout(
                view!!.context
            ).apply {
                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    dp(16)
                )
            }

        val timeout =
            EditText(view!!.context).apply {
                hint = "Timeout (ms)"
                inputType =
                    InputType.TYPE_CLASS_NUMBER

                setText(
                    this@BlockController.timeoutMs
                        .toString()
                )
            }

        val mode =
            Spinner(view!!.context)

        mode.adapter =
            ArrayAdapter(
                view!!.context,
                android.R.layout.simple_spinner_item,
                listOf(
                    "Exact",
                    "Regular expression"
                )
            )

        mode.setSelection(
            if (match == "regex") {
                1
            } else {
                0
            }
        )

        val initial_text =
            EditText(view!!.context).apply {
                hint = "Pattern"
                setText(
                    this@BlockController.text
                )
                inputType =
                    InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }

        val ignore_case =
            CheckBox(view!!.context).apply {
                text =
                    "Ignore case"
                isChecked =
                    this@BlockController.ignoreCase
            }

        val escapes =
            CheckBox(view!!.context).apply {
                text =
                    "Interpret \\r, \\n, \\xhh..."
                isChecked =
                    interpretEscapes
            }

        layout.addView(timeout)
        layout.addView(mode)
        layout.addView(initial_text)
        layout.addView(ignore_case)
        layout.addView(escapes)

        android.app.AlertDialog.Builder(
            view!!.context
        )
            .setTitle(
                R.string.sim_custom_serial_script_receive
            )
            .setView(layout)
            .setPositiveButton(
                android.R.string.ok
            ) { _, _ ->
                timeoutMs =
                    timeout.text
                        .toString()
                        .toIntOrNull()
                        ?.coerceAtLeast(0)
                        ?: 0

                match =
                    if (
                        mode.selectedItemPosition == 1
                    ) {
                        "regex"
                    } else {
                        "exact"
                    }

                text =
                    initial_text.text.toString()

                ignoreCase =
                    ignore_case.isChecked

                interpretEscapes =
                    escapes.isChecked

                onDataChanged()
            }
            .setNegativeButton(
                android.R.string.cancel,
                null
            )
            .show()
    }

    private fun editSend() {
        val layout =
            LinearLayout(
                view!!.context
            ).apply {
                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    dp(16)
                )
            }

        val timeout =
            EditText(view!!.context).apply {
                hint = "Timeout (ms)"
                inputType =
                    InputType.TYPE_CLASS_NUMBER

                setText(
                    this@BlockController.timeoutMs
                        .toString()
                )
            }

        val initial_text =
            EditText(view!!.context).apply {
                hint = "ASCII text"
                setText(
                    this@BlockController.text
                )
                inputType =
                    InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }

        val eol =
            CheckBox(view!!.context).apply {
                text =
                    "Automatically append EOL"
                isChecked =
                    includeEol
            }

        val escapes =
            CheckBox(view!!.context).apply {
                text =
                    "Interpret \\r, \\n, \\xhh..."
                isChecked =
                    interpretEscapes
            }

        layout.addView(timeout)
        layout.addView(initial_text)
        layout.addView(eol)
        layout.addView(escapes)

        android.app.AlertDialog.Builder(
            view!!.context
        )
            .setTitle(
                R.string.sim_custom_serial_script_send
            )
            .setView(layout)
            .setPositiveButton(
                android.R.string.ok
            ) { _, _ ->
                timeoutMs =
                    timeout.text
                        .toString()
                        .toIntOrNull()
                        ?.coerceAtLeast(0)
                        ?: 0

                text =
                    initial_text.text.toString()

                includeEol =
                    eol.isChecked

                interpretEscapes =
                    escapes.isChecked

                onDataChanged()
            }
            .setNegativeButton(
                android.R.string.cancel,
                null
            )
            .show()
    }

    private fun editContainer() {
        val input =
            EditText(view!!.context).apply {
                setText(
                    this@BlockController.name
                )
            }

        android.app.AlertDialog.Builder(
            view!!.context
        )
            .setTitle(
                R.string.sim_custom_serial_script_container
            )
            .setView(input)
            .setPositiveButton(
                android.R.string.ok
            ) { _, _ ->
                name =
                    input.text.toString()

                onDataChanged()
            }
            .setNegativeButton(
                android.R.string.cancel,
                null
            )
            .show()
    }

    // TEMP area
    fun dp(
        amount: Int
    ): Int {
        return view!!
            .parentView!!
            .model!!
            .dp(amount)
    }
    // End TEMP area
}