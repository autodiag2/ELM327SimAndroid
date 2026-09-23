package com.github.autodiag2.elm327emu.sim.serial

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
        private val blockStandardContentPadding : Float = 40f
        public val blockBorderWidthSelected: Float = 10f
        public val blockBorderWidth: Float = 3f
        private val portRadius = 20f
        private val rect = RectF()
        private val portPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val executionIndicatorRadius = 7f
        private val executionIndicatorPadding = 10f
        private val executionIndicatorFlashPeriod = 500L
        private val executionIndicatorFlashOnTime = 250L

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
                    "${model!!.timeoutMs}ms"

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
                System.currentTimeMillis() % 500L

            if (phase >= 250L) {
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
                        "${model!!.timeoutMs}ms"

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