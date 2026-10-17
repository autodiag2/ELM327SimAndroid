package com.github.autodiag2.elm327emu.sim.serial

import com.github.autodiag2.elm327emu.R
import com.github.autodiag2.elm327emu.sim.serial.CustomView.Coordinates

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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

import android.util.Log
import androidx.core.view.setPadding

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
        private val blockStandardContentPadding: Float = 40f
        public val blockBorderWidthSelected: Float = 10f
        public val blockBorderWidth: Float = 3f
        private val portRadius = 20f
        private val rect = RectF()
        private val portPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val executionIndicatorRadius = 7f
        private val executionIndicatorPadding = 10f
        private val executionIndicatorFlashPeriod = 500L
        private val executionIndicatorFlashOnTime = 250L
        private val summaryMaxLines = 10
        private val foldIndicatorSize = 10f

        init {
            paint.style = Paint.Style.FILL
            portPaint.style = Paint.Style.FILL
        }
    }

    init {
        textPaint.textSize = getTextSize()
    }

    private fun getExecutionColor(
        block: BlockController
    ): Int? {
        return when (block.state) {
            BlockController.State.IDLE ->
                null

            BlockController.State.IN_PROGRESS ->
                getThemeColor(
                    R.attr.colorAccentInProgress
                )

            BlockController.State.SUCCESS ->
                getThemeColor(
                    R.attr.colorAccentSuccess
                )

            BlockController.State.FAILED ->
                getThemeColor(
                    R.attr.colorAccentFailed
                )
        }
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
         * A folded container only needs enough room for its title
         * and fold indicator. Its children retain their positions.
         */
        if (isFolded) {
            width = max(
                260f,
                titleRequiredWidth + 48f
            )

            height = max(
                140f,
                titleRequiredHeight + containerTitleHeight
            )

            return
        }

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
        if (blockView.model!!.parent == null) {
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

    private fun getSummary(maxLines: Int = summaryMaxLines): List<String> {
        val summary = when (model!!.type) {
            BlockController.Type.DELAY ->
                "${model!!.timeoutMs}ms"

            BlockController.Type.SEND ->
                model!!.text

            BlockController.Type.RECV ->
                model!!.text

            BlockController.Type.LOG -> model!!.text
            BlockController.Type.AND,
            BlockController.Type.OR,
            BlockController.Type.XOR,
            BlockController.Type.NOT,
            BlockController.Type.CLIENT_CONNECTED,
            BlockController.Type.CLIENT_DISCONNECTED,
            BlockController.Type.CONTAINER ->
                ""
        }

        return summary
            .split(Regex("\\\\\r\\\\\n|\\\\\r|\\\\\n|\r\n|\r|\n"))
            .take(maxLines.coerceAtLeast(0))
    }

    private fun updateBlockContentSize() {
        if (
            model!!.type ==
            BlockController.Type.CONTAINER
        ) {
            return
        }

        val title = model!!.name
        val summary = getSummary()

        val titlePaint = TextPaint(textPaint)
        val summaryPaint = TextPaint(textPaint)

        val titleBounds = android.graphics.Rect()
        titlePaint.getTextBounds(
            title,
            0,
            title.length,
            titleBounds
        )

        val summaryBounds = summary.map { line ->
            android.graphics.Rect().also { bounds ->
                summaryPaint.getTextBounds(
                    line,
                    0,
                    line.length,
                    bounds
                )
            }
        }

        val lineSpacing = 4f

        val requiredWidth =
            max(
                titleBounds.width(),
                summaryBounds.maxOfOrNull { it.width() } ?: 0
            ) +
            blockStandardContentPadding * 2f

        val requiredHeight =
            titleBounds.height() +
            lineSpacing +
            summaryBounds.sumOf { it.height() }.toFloat() +
            lineSpacing * (summary.size - 1).coerceAtLeast(0) +
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

    fun isRunning(): Boolean {
        return parentView.model!!.isRunning()
    }

    private fun drawExecutionIndicator(
        canvas: Canvas,
        nodeWorldPos: Coordinates
    ) {
        if (model!!.state == BlockController.State.IDLE) {
            return
        }

        if (
            model!!.state == BlockController.State.IN_PROGRESS &&
            isRunning()
        ) {
            val phase =
                System.currentTimeMillis() % executionIndicatorFlashPeriod

            if (phase >= executionIndicatorFlashOnTime) {
                return
            }
        }

        val color =
            when (model!!.state) {
                BlockController.State.IDLE ->
                    return

                BlockController.State.IN_PROGRESS -> {
                    getThemeColor(
                        R.attr.colorAccentInProgress
                    )
                }

                BlockController.State.SUCCESS ->
                    getThemeColor(
                        R.attr.colorAccentSuccess
                    )

                BlockController.State.FAILED ->
                    getThemeColor(
                        R.attr.colorAccentFailed
                    )
            }

        paint.style = Paint.Style.FILL
        paint.color = color

        canvas.drawCircle(
            nodeWorldPos.x +
                executionIndicatorPadding +
                executionIndicatorRadius,
            nodeWorldPos.y +
                executionIndicatorPadding +
                executionIndicatorRadius,
            executionIndicatorRadius,
            paint
        )
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

        drawExecutionIndicator(
            canvas,
            nodeWorldPos
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

        paint.color = if (selected) {
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

            /*
             * Draw the fold indicator at the top-right.
             */
            val size = foldIndicatorSize
            val centerX = nodeWorldPos.x + width - 28f
            val centerY = nodeWorldPos.y + blockStandardContentPadding

            val path = Path()

            if (isFolded) {
                path.moveTo(centerX - size * 0.7f, centerY - size)
                path.lineTo(centerX - size * 0.7f, centerY + size)
                path.lineTo(centerX + size * 0.7f, centerY)
            } else {
                path.moveTo(centerX - size, centerY - size * 0.7f)
                path.lineTo(centerX + size, centerY - size * 0.7f)
                path.lineTo(centerX, centerY + size * 0.7f)
            }

            path.close()
            paint.style = Paint.Style.FILL
            paint.color = textColor
            canvas.drawPath(path, paint)
        } else {
            /*
             * Non-container blocks have a title and multiple summary lines.
             */

            val title = model!!.name
            val summaryLines = getSummary()

            logDebug(
                "Block ${model!!.id} summary lines: ${summaryLines}"
            )

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

            val titleBounds =
                android.graphics.Rect()

            titlePaint.getTextBounds(
                titleText,
                0,
                titleText.length,
                titleBounds
            )

            val summaryItems = summaryLines.map { line ->
                val text =
                    TextUtils.ellipsize(
                        line,
                        summaryPaint,
                        contentWidth,
                        TextUtils.TruncateAt.END
                    ).toString()

                val bounds =
                    android.graphics.Rect()

                summaryPaint.getTextBounds(
                    text,
                    0,
                    text.length,
                    bounds
                )

                Triple(
                    text,
                    bounds,
                    summaryPaint.measureText(text)
                )
            }

            val lineSpacing = 4f

            val summaryHeight =
                summaryItems.sumOf {
                    it.second.height()
                }.toFloat() +
                lineSpacing * (summaryItems.size - 1).coerceAtLeast(0)

            val totalHeight =
                titleBounds.height() +
                lineSpacing +
                summaryHeight

            var currentTop =
                contentTop +
                max(
                    0f,
                    (contentHeight - totalHeight) / 2f
                )

            val titleBaseline =
                currentTop - titleBounds.top

            canvas.drawText(
                titleText,
                (contentLeft + contentRight - titlePaint.measureText(titleText)) / 2f,
                titleBaseline,
                titlePaint
            )

            currentTop +=
                titleBounds.height() + lineSpacing

            for ((text, bounds, textWidth) in summaryItems) {
                val baseline =
                    currentTop - bounds.top

                canvas.drawText(
                    text,
                    (contentLeft + contentRight - textWidth) / 2f,
                    baseline,
                    summaryPaint
                )

                currentTop +=
                    bounds.height() + lineSpacing
            }
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

        if (isContainer && isFolded) {
            parentView.markDescendantsDrawn(
                model!!,
                drawn
            )
        } else {
            for (childId in model!!.children) {
                val child = containerResolveChild(childId)
                child?.view?.draw(
                    canvas,
                    drawn
                )
            }
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
            if (portIsDestination() || selected) {
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

    var isFolded: Boolean = false
        private set

    fun toggleFold() {
        if (model?.type != BlockController.Type.CONTAINER) {
            return
        }

        isFolded = !isFolded
        updateContainerBounds()
        parentView.refresh()
    }

    fun containsFoldToggle(x: Float, y: Float): Boolean {
        if (
            model?.type != BlockController.Type.CONTAINER
        ) {
            return false
        }

        val pos = getWorldCoords()

        return x >= pos.x + width - 56f &&
            x <= pos.x + width &&
            y >= pos.y &&
            y <= pos.y + blockStandardContentPadding * 2f
    }
}