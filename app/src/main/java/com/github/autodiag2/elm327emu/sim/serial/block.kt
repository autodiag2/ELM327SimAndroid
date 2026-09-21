package com.github.autodiag2.elm327emu.sim.serial

import org.json.JSONArray
import org.json.JSONObject

// view imports
import com.github.autodiag2.elm327emu.R
import com.github.autodiag2.elm327emu.sim.serial.CustomView.Coordinates
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.InputType
import android.text.TextPaint
import android.text.TextUtils
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import kotlin.math.max
import androidx.core.view.setPadding
// End view imports

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
            setText(text)
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
            setText(text)
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
            setText(name)
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
open class BlockView(
    /**
     * Relative to container
     */
    var x: Float,
    /**
     * Relative to container
     */
    var y: Float,
    context: Context,
    var width: Float = 260f,
    var height: Float = 100f,
    model: BlockController? = null,
    public val parentView: CustomView
) : ElementView<BlockController>(model = model, context = context) {
    
    companion object {
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val containerTitleHeight = 40f
        private val blockStandardContentPadding : Float = 40f
        public val blockBorderWidthSelected: Float = 10f
        public val blockBorderWidth: Float = 3f
        private val portRadius = 20f
        private val rect = RectF()
        private val portPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            paint.style = Paint.Style.FILL
            portPaint.style = Paint.Style.FILL
        }

    }

    init {
        textPaint.textSize = getTextSize()
    }

    // ----- Temp region -----
    private fun getTextSize(): Float {
        return 16f * parentView.resources.displayMetrics.scaledDensity
    }
    public fun logDebug(message: String) {
        parentView.logDebug(message)
    }

    private fun containerResolveChilds(): List<BlockController> {
        return model!!.children.mapNotNull { childId ->
            containerResolveChild(childId)
        }
    }

    private fun containerResolveChild(childId: Int): BlockController? {
        return parentView.model!!.blocks.find {
            it.id == childId
        }
    }
    private fun portIsDestination(): Boolean {
        return parentView.hoveredDestination?.model?.id == model!!.id
    }
    private fun portIsSource(): Boolean {
        return parentView.linkingFrom?.model?.id == model!!.id
    }   
    // ----- End Temp region -----

    public fun updateContainerBounds() {
        val children = containerResolveChilds()

        /*
        * Update nested containers first.
        */
        for (childBlock in children) {
            if (
                childBlock.type ==
                BlockController.Type.CONTAINER
            ) {
                childBlock.view!!.updateContainerBounds()
            }
        }

        /*
        * ------------------------------------------------------------
        * Minimum size required by the container title.
        * ------------------------------------------------------------
        */

        val titleWidth =
            getTextWidth(
                model!!.name
            )

        val titleHeight =
            textPaint.fontMetrics.bottom -
            textPaint.fontMetrics.top

        val titleRequiredWidth =
            titleWidth +
            blockStandardContentPadding * 2f

        val titleRequiredHeight =
            titleHeight +
            blockStandardContentPadding * 2f

        /*
        * ------------------------------------------------------------
        * Size required by children.
        * ------------------------------------------------------------
        */

        var childrenRequiredWidth = 0f
        var childrenRequiredHeight = 0f

        for (childBlock in children) {
            val child = childBlock.view!!

            childrenRequiredWidth =
                max(
                    childrenRequiredWidth,
                    child.x + child.width
                )

            childrenRequiredHeight =
                max(
                    childrenRequiredHeight,
                    child.y + child.height
                )
        }

        /*
        * Children are positioned in the container's local
        * coordinate system. Keep the existing container padding
        * around them and reserve the title area.
        */
        val childrenWidth =
            childrenRequiredWidth +
            blockStandardContentPadding

        val childrenHeight =
            childrenRequiredHeight +
            blockStandardContentPadding +
            containerTitleHeight

        /*
        * ------------------------------------------------------------
        * Final container size.
        * ------------------------------------------------------------
        */

        width =
            max(
                260f,
                max(
                    titleRequiredWidth,
                    childrenWidth
                )
            )

        height =
            max(
                140f,
                max(
                    titleRequiredHeight + containerTitleHeight,
                    childrenHeight
                )
            )
    }
    
    private fun getWorldCoordsRecurse(blockView: BlockView): Coordinates {
        if ( blockView.model!!.parent == null ) {
            return Coordinates(blockView.x, blockView.y)
        } else {
            val coords = getWorldCoordsRecurse(blockView.model!!.parent!!.view!!)
            coords.x += blockView.x
            coords.y += blockView.y
            return coords
        }
    }

    private fun getTextWidth(
        text: String
    ): Float {
        return textPaint.measureText(text)
    }

    /**
     * Get absolute position from the node contained relative ones
     */
    public fun getWorldCoords(): Coordinates {
        return getWorldCoordsRecurse(this)
    }

    private fun updateBlockContentSize() {
        if (
            model!!.type ==
            BlockController.Type.CONTAINER
        ) {
            return
        }

        val title = model!!.name

        val summary =
            when (model!!.type) {
                BlockController.Type.DELAY ->
                    "${model!!.delay}ms"

                BlockController.Type.SEND ->
                    model!!.text

                BlockController.Type.RECV ->
                    model!!.text

                BlockController.Type.CONTAINER ->
                    ""
            }

        val titlePaint =
            TextPaint(textPaint)

        val summaryPaint =
            TextPaint(textPaint)

        val titleBounds =
            android.graphics.Rect()

        val summaryBounds =
            android.graphics.Rect()

        titlePaint.getTextBounds(
            title,
            0,
            title.length,
            titleBounds
        )

        summaryPaint.getTextBounds(
            summary,
            0,
            summary.length,
            summaryBounds
        )

        val lineSpacing = 4f

        val requiredWidth =
            max(
                titleBounds.width(),
                summaryBounds.width()
            ) +
            blockStandardContentPadding * 2f

        val requiredHeight =
            titleBounds.height() +
            lineSpacing +
            summaryBounds.height() +
            blockStandardContentPadding * 2f

        width =
            max(
                260f,
                requiredWidth
            )

        height =
            max(
                100f,
                requiredHeight
            )
    }

    public fun isSelected(): Boolean {
        return parentView.model!!.isBlockSelected(model!!)
    }

    public fun draw(
        canvas: Canvas,
        drawn: MutableSet<Int>
    ) {
        if (!drawn.add(model!!.id)) {
            return
        }

        updateBlockContentSize()
        val nodeWorldPos = getWorldCoords()

        rect.set(
            nodeWorldPos.x,
            nodeWorldPos.y,
            nodeWorldPos.x + width,
            nodeWorldPos.y + height
        )

        logDebug(
            "DRAW BLOCK ${model!!.id} at ${rect}"
        )

        val selected = isSelected()
        val colorPrimaryDark =
            getThemeColor(
                androidx.appcompat.R.attr.colorPrimaryDark
            )

        val textColor =
            getThemeColor(
                android.R.attr.textColor
            )

        val accentColor =
            getThemeColor(
                androidx.appcompat.R.attr.colorAccent
            )

        val isContainer =
            model!!.type ==
                BlockController.Type.CONTAINER

        /*
        * ------------------------------------------------------------
        * Block background
        * ------------------------------------------------------------
        */

        paint.style = Paint.Style.FILL
        paint.color = colorPrimaryDark

        val cornerRadius =
            if (isContainer) 18f else 14f

        canvas.drawRoundRect(
            rect,
            cornerRadius,
            cornerRadius,
            paint
        )

        /*
        * ------------------------------------------------------------
        * Block border
        * ------------------------------------------------------------
        */

        paint.style = Paint.Style.STROKE

        paint.strokeWidth =
            if (selected) {
                blockBorderWidthSelected
            } else {
                blockBorderWidth
            }

        paint.color =
            if (selected) {
                accentColor
            } else {
                textColor
            }

        canvas.drawRoundRect(
            rect,
            cornerRadius,
            cornerRadius,
            paint
        )

        /*
        * ------------------------------------------------------------
        * Block text
        * ------------------------------------------------------------
        */

        textPaint.color = textColor

        if (isContainer) {
            /*
            * Containers have their title near the top.
            */
            val fontMetrics =
                textPaint.fontMetrics

            val textX =
                nodeWorldPos.x +
                blockStandardContentPadding

            val textY =
                nodeWorldPos.y +
                blockStandardContentPadding -
                (fontMetrics.ascent + fontMetrics.descent) / 2f

            canvas.drawText(
                model!!.name,
                textX,
                textY,
                textPaint
            )
        } else {
            /*
            * Non-container blocks have:
            *
            *     title
            *     summary
            *
            * both centered in the 
            */
            val title = model!!.name

            val summary =
                when (model!!.type) {
                    BlockController.Type.DELAY ->
                        "${model!!.delay}ms"

                    BlockController.Type.SEND ->
                        model!!.text

                    BlockController.Type.RECV ->
                        model!!.text

                    BlockController.Type.CONTAINER ->
                        ""
                }

            val contentLeft =
                nodeWorldPos.x + blockStandardContentPadding

            val contentRight =
                nodeWorldPos.x +
                    width -
                    blockStandardContentPadding

            val contentTop =
                nodeWorldPos.y + blockStandardContentPadding

            val contentBottom =
                nodeWorldPos.y +
                    height -
                    blockStandardContentPadding

            val contentWidth =
                max(
                    0f,
                    contentRight - contentLeft
                )

            val contentHeight =
                max(
                    0f,
                    contentBottom - contentTop
                )

            val titlePaint =
                TextPaint(textPaint).apply {
                    color = textColor
                }

            val summaryPaint =
                TextPaint(textPaint).apply {
                    color =
                        getThemeColor(
                            android.R.attr.textColorSecondary
                        )
                }

            val titleText =
                TextUtils.ellipsize(
                    title,
                    titlePaint,
                    contentWidth,
                    TextUtils.TruncateAt.END
                ).toString()

            val summaryText =
                TextUtils.ellipsize(
                    summary,
                    summaryPaint,
                    contentWidth,
                    TextUtils.TruncateAt.END
                ).toString()

            val titleWidth =
                titlePaint.measureText(titleText)

            val summaryWidth =
                summaryPaint.measureText(summaryText)

            val titleBounds =
                android.graphics.Rect()

            val summaryBounds =
                android.graphics.Rect()

            titlePaint.getTextBounds(
                titleText,
                0,
                titleText.length,
                titleBounds
            )

            summaryPaint.getTextBounds(
                summaryText,
                0,
                summaryText.length,
                summaryBounds
            )

            val lineSpacing = 4f

            val totalHeight =
                titleBounds.height() +
                lineSpacing +
                summaryBounds.height()

            val startTop =
                contentTop +
                    max(
                        0f,
                        (contentHeight - totalHeight) / 2f
                    )

            val titleBaseline =
                startTop - titleBounds.top

            val summaryTop =
                startTop +
                    titleBounds.height() +
                    lineSpacing

            val summaryBaseline =
                summaryTop - summaryBounds.top

            canvas.drawText(
                titleText,
                (contentLeft + contentRight - titleWidth) / 2f,
                titleBaseline,
                titlePaint
            )

            canvas.drawText(
                summaryText,
                (contentLeft + contentRight - summaryWidth) / 2f,
                summaryBaseline,
                summaryPaint
            )
        }

        /*
        * ------------------------------------------------------------
        * Ports
        * ------------------------------------------------------------
        */

        drawPorts(
            canvas
        )

        /*
        * ------------------------------------------------------------
        * Children
        * ------------------------------------------------------------
        */

        for (childId in model!!.children) {
            val child = containerResolveChild(childId)
            child?.view?.draw(
                canvas,
                drawn
            )
        }
    }    

    private fun drawPorts(
        canvas: Canvas
    ) {
        val selected = isSelected()

        val colorAccent = getThemeColor(
            androidx.appcompat.R.attr.colorAccent
        )

        val nodeWorldPos = getWorldCoords()

        portPaint.color =
            if ( portIsDestination() || selected ) {
                colorAccent
            } else {
                0xff555555.toInt()
            }

        canvas.drawCircle(
            nodeWorldPos.x,
            nodeWorldPos.y + height / 2f,
            portRadius,
            portPaint
        )

        portPaint.color =
            if (portIsSource() || selected) {
                colorAccent
            } else {
                0xff555555.toInt()
            }

        canvas.drawCircle(
            nodeWorldPos.x + width,
            nodeWorldPos.y + height / 2f,
            portRadius,
            portPaint
        )
    }

    private fun containerGetUsableArea(
        container: BlockView
    ): RectF {
        return RectF(
            blockStandardContentPadding,
            containerTitleHeight + blockStandardContentPadding,
            container.width - blockStandardContentPadding,
            container.height - blockStandardContentPadding
        )
    }
}