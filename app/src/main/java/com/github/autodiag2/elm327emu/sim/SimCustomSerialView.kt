package com.github.autodiag2.elm327emu.sim

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import com.github.autodiag2.elm327emu.sim.SimCustomSerialController
import android.util.Log
import com.github.autodiag2.elm327emu.BuildConfig
import androidx.core.content.ContextCompat
import com.github.autodiag2.elm327emu.R
import android.util.TypedValue

class SimCustomSerialView(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Coordinates(
        public var x: Float = 0f,
        public var y: Float = 0f
    )
    open class Block(
        /**
         * Relative to container
         */
        var x: Float,
        /**
         * Relative to container
         */
        var y: Float,
        var width: Float = 260f,
        var height: Float = 100f,
        val children: MutableList<Int> = mutableListOf(),
        model: SimCustomSerialController.Block? = null
    ) : SimCustomSerialController.ElementView<SimCustomSerialController.Block>(model = model) {
        
        private fun getWorldCoordsRecurse(node: Block): Coordinates {
            if ( node.model!!.parent == null ) {
                return Coordinates(node.x, node.y)
            } else {
                val coords = getWorldCoordsRecurse(node.model!!.parent!!.view!!)
                coords.x += node.x
                coords.y += node.y
                return coords
            }
        }

        /**
         * Get absolute position from the node contained relative ones
         */
        public fun getWorldCoords(): Coordinates {
            return getWorldCoordsRecurse(this)
        }
    }

    open class Link(
        model: SimCustomSerialController.Link? = null
    ) : SimCustomSerialController.ElementView<SimCustomSerialController.Link>(model = model)

    interface Listener {
        fun onBlockClicked(node: Block)
        fun onBlockLongClicked(node: Block)
        fun onCreateLink(from: Block)
        fun onLinkToBlock(from: Block, to: Block)
        fun onBlockIncluded(parent: Block, child: Block)
        fun onBlockExcluded(parent: Block, child: Block)
        fun onElementSelected(view: SimCustomSerialController.ElementView<*>)
        fun onElementUnselected(view: SimCustomSerialController.ElementView<*>)
        fun onUnselectAll()
    }

    var model: SimCustomSerialController? = null

    // --------- Customization settings ---------
    private var scale = 1f
    private val containerPadding = 50f
    private val containerTitleHeight = 40f
    private val portRadius = 20f
    private val portHitRadius = 100f
    private val linkArrowSize = 30f
    // --------- End Customization settings ---------

    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val containerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val connectionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectedLinkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linkPreviewPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val portPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val nodeRect = RectF()
    private val containerRect = RectF()

    private val gestureDetector: GestureDetector
    private val scaleDetector: ScaleGestureDetector

    private var offsetX = 0f
    private var offsetY = 0f

    private var draggingBlock: Block? = null

    private var linkingFrom: Block? = null
    private var hoveredDestination: Block? = null

    private var linkX = 0f
    private var linkY = 0f

    private var lastX = 0f
    private var lastY = 0f
    private var lastPointerCount = 0

    private var movedDuringGesture = false

    init {
        nodePaint.style = Paint.Style.FILL
        containerPaint.style = Paint.Style.FILL

        textPaint.textSize =
            16f * resources.displayMetrics.scaledDensity

        connectionPaint.style = Paint.Style.STROKE
        connectionPaint.strokeWidth = 4f
        connectionPaint.strokeCap = Paint.Cap.ROUND

        arrowPaint.style = Paint.Style.FILL

        selectedLinkPaint.style = Paint.Style.STROKE
        selectedLinkPaint.strokeWidth = 7f
        selectedLinkPaint.strokeCap = Paint.Cap.ROUND

        linkPreviewPaint.style = Paint.Style.STROKE
        linkPreviewPaint.strokeWidth = 5f
        linkPreviewPaint.strokeCap = Paint.Cap.ROUND

        portPaint.style = Paint.Style.FILL

        gestureDetector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {

                override fun onDown(
                    event: MotionEvent
                ): Boolean {
                    return true
                }

                override fun onSingleTapUp(
                    event: MotionEvent
                ): Boolean {
                    if (movedDuringGesture ||
                        linkingFrom != null
                    ) {
                        return true
                    }

                    val node = findBlock(
                        event.x,
                        event.y
                    )

                    if (node != null) {
                        model?.onUnselectAll()
                        model?.onElementSelected(node)
                        model?.onBlockClicked(node)

                        invalidate()
                        return true
                    }

                    val connection = findLink(
                        event.x,
                        event.y
                    )

                    if (connection != null) {
                        model?.onUnselectAll()
                        model?.onElementSelected(connection)

                        invalidate()
                        return true
                    }

                    model?.onUnselectAll()

                    invalidate()

                    return true
                }

                override fun onLongPress(
                    event: MotionEvent
                ) {
                    val source = findSourcePort(
                        event.x,
                        event.y
                    )

                    if (source != null) {
                        linkingFrom = source
                        model?.onUnselectAll()

                        val point =
                            screenToWorld(
                                event.x,
                                event.y
                            )

                        linkX = point.first
                        linkY = point.second

                        invalidate()
                        return
                    }

                    val node = findBlock(
                        event.x,
                        event.y
                    )

                    if (node != null) {
                        model?.onUnselectAll()
                        model?.onElementSelected(node)

                        invalidate()
                        return
                    }

                    val connection = findLink(
                        event.x,
                        event.y
                    )

                    if (connection != null) {
                        model?.onUnselectAll()
                        model?.onElementSelected(connection)

                        invalidate()
                        return
                    }

                    model?.onUnselectAll()

                    invalidate()
                }
            }
        )

        scaleDetector = ScaleGestureDetector(
            context,
            object :
                ScaleGestureDetector.SimpleOnScaleGestureListener() {

                override fun onScale(
                    detector: ScaleGestureDetector
                ): Boolean {
                    val oldScale = scale

                    val newScale =
                        (scale * detector.scaleFactor)
                            .coerceIn(0.35f, 2.5f)

                    val focusX = detector.focusX
                    val focusY = detector.focusY

                    val worldX =
                        (focusX - offsetX) / oldScale

                    val worldY =
                        (focusY - offsetY) / oldScale

                    scale = newScale

                    offsetX =
                        focusX - worldX * newScale

                    offsetY =
                        focusY - worldY * newScale

                    invalidate()

                    return true
                }
            }
        )
    }

    private fun logDebug(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d("SimCustomSerialView", message)
        }
    }

    private fun screenToWorld(
        screenX: Float,
        screenY: Float
    ): Pair<Float, Float> {
        return Pair(
            (screenX - offsetX) / scale,
            (screenY - offsetY) / scale
        )
    }

    private fun getThemeColor(
        attr: Int
    ): Int {
        val typedValue = TypedValue()

        context.theme.resolveAttribute(
            attr,
            typedValue,
            true
        )

        return typedValue.data
    }

    private fun drawArrow(
        canvas: Canvas,
        x: Float,
        y: Float,
        angle: Float,
        paint: Paint
    ) {

        val path = Path()

        path.moveTo(
            x + kotlin.math.cos(angle) * linkArrowSize,
            y + kotlin.math.sin(angle) * linkArrowSize
        )

        path.lineTo(
            x + kotlin.math.cos(angle + 2.5f) * linkArrowSize,
            y + kotlin.math.sin(angle + 2.5f) * linkArrowSize
        )

        path.lineTo(
            x + kotlin.math.cos(angle - 2.5f) * linkArrowSize,
            y + kotlin.math.sin(angle - 2.5f) * linkArrowSize
        )

        path.close()

        canvas.drawPath(
            path,
            paint
        )
    }

    public fun refresh() {
        invalidate()
    }

    public fun addBlock(
        model: SimCustomSerialController.Block
    ): Block {
        return Block(0f, 0f, model = model)
    }

    public fun addLink(model: SimCustomSerialController.Link): Link {
        return Link(model = model)
    }

    override fun onDraw(
        canvas: Canvas
    ) {
        super.onDraw(canvas)

        canvas.save()

        canvas.translate(
            offsetX,
            offsetY
        )

        canvas.scale(
            scale,
            scale
        )

        updateAllContainerBounds()

        drawBlocks(canvas)
        drawLinks(canvas)
        drawLinkPreview(canvas)

        canvas.restore()
    }

    private fun drawLinkCurve(
        canvas: Canvas,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        paint: Paint
    ) {
        val distance =
            max(
                40f,
                abs(endX - startX) * 0.5f
            )

        val control1X = startX + distance
        val control1Y = startY
        val control2X = endX - distance
        val control2Y = endY

        val path = Path()

        path.moveTo(
            startX,
            startY
        )

        path.cubicTo(
            control1X,
            control1Y,
            control2X,
            control2Y,
            endX,
            endY
        )

        canvas.drawPath(
            path,
            paint
        )

        val t = 0.5f
        val inverse = 1f - t

        val arrowX =
            inverse * inverse * inverse * startX +
            3f * inverse * inverse * t * control1X +
            3f * inverse * t * t * control2X +
            t * t * t * endX

        val arrowY =
            inverse * inverse * inverse * startY +
            3f * inverse * inverse * t * control1Y +
            3f * inverse * t * t * control2Y +
            t * t * t * endY

        val tangentX =
            3f * inverse * inverse * (control1X - startX) +
            6f * inverse * t * (control2X - control1X) +
            3f * t * t * (endX - control2X)

        val tangentY =
            3f * inverse * inverse * (control1Y - startY) +
            6f * inverse * t * (control2Y - control1Y) +
            3f * t * t * (endY - control2Y)

        val angle =
            kotlin.math.atan2(
                tangentY,
                tangentX
            )

        arrowPaint.color = paint.color

        drawArrow(
            canvas,
            arrowX,
            arrowY,
            angle,
            arrowPaint
        )
    }
    private fun updateAllContainerBounds() {
        for (block in model!!.blocks) {
            if (block.type ==
                SimCustomSerialController.Block.Type.CONTAINER
            ) {
                updateContainerBounds(block.view!!)
            }
        }
    }

    private fun updateContainerBounds(
        container: Block
    ) {
        val children =
            container.children.mapNotNull { childId ->
                model!!.blocks.find {
                    it.id == childId
                }
            }

        if (children.isEmpty()) {
            container.width =
                max(
                    container.width,
                    260f
                )

            container.height =
                max(
                    container.height,
                    140f
                )

            return
        }

        for (childBlock in children) {
            if (
                childBlock.type ==
                SimCustomSerialController.Block.Type.CONTAINER
            ) {
                updateContainerBounds(
                    childBlock.view!!
                )
            }
        }

        var right = 0f
        var bottom = 0f

        for (childBlock in children) {
            val child = childBlock.view!!

            right =
                max(
                    right,
                    child.x + child.width
                )

            bottom =
                max(
                    bottom,
                    child.y + child.height
                )
        }

        container.width =
            max(
                260f,
                right + containerPadding
            )

        container.height =
            max(
                140f,
                bottom +
                    containerPadding +
                    containerTitleHeight
            )
    }

    private fun drawBlock(
        canvas: Canvas,
        block: SimCustomSerialController.Block,
        drawn: MutableSet<Int>
    ) {
        if (!drawn.add(block.id)) {
            return
        }

        val node = block.view!!
        val nodeWorldPos = node.getWorldCoords()

        nodeRect.set(
            nodeWorldPos.x,
            nodeWorldPos.y,
            nodeWorldPos.x + node.width,
            nodeWorldPos.y + node.height
        )

        logDebug(
            "DRAW BLOCK ${block.id} at ${nodeRect}"
        )

        val selected =
            model!!.isBlockSelected(block)

        val colorPrimaryDark =
            getThemeColor(
                androidx.appcompat.R.attr.colorPrimaryDark
            )

        val textColor =
            getThemeColor(
                android.R.attr.textColor
            )

        if (
            block.type ==
            SimCustomSerialController.Block.Type.CONTAINER
        ) {
            nodePaint.style =
                Paint.Style.FILL

            nodePaint.color =
                if (selected) {
                    0x332196f3
                } else {
                    0x18000000
                }

            canvas.drawRoundRect(
                nodeRect,
                18f,
                18f,
                nodePaint
            )

            nodePaint.style =
                Paint.Style.STROKE

            nodePaint.strokeWidth =
                if (selected) 5f else 3f

            nodePaint.color =
                if (selected) {
                    colorPrimaryDark
                } else {
                    textColor
                }

            canvas.drawRoundRect(
                nodeRect,
                18f,
                18f,
                nodePaint
            )

            nodePaint.style =
                Paint.Style.FILL

            textPaint.color =
                textColor

            val fontMetrics =
                textPaint.fontMetrics

            val textX =
                nodeWorldPos.x +
                containerPadding

            val textY =
                nodeWorldPos.y +
                containerPadding -
                (fontMetrics.ascent + fontMetrics.descent) / 2f

            canvas.drawText(
                block.name,
                textX,
                textY,
                textPaint
            )

            for (childId in block.children) {
                val child =
                    model!!.blocks.firstOrNull {
                        it.id == childId
                    }

                if (child != null) {
                    drawBlock(
                        canvas,
                        child,
                        drawn
                    )
                }
            }

            return
        }

        nodePaint.style =
            Paint.Style.FILL

        nodePaint.color =
            if (selected) {
                textColor
            } else {
                colorPrimaryDark
            }

        canvas.drawRoundRect(
            nodeRect,
            14f,
            14f,
            nodePaint
        )

        nodePaint.style =
            Paint.Style.STROKE

        nodePaint.strokeWidth = 3f

        nodePaint.color =
            if (selected) {
                colorPrimaryDark
            } else {
                textColor
            }

        canvas.drawRoundRect(
            nodeRect,
            14f,
            14f,
            nodePaint
        )

        nodePaint.style =
            Paint.Style.FILL

        textPaint.color =
            if (selected) {
                colorPrimaryDark
            } else {
                textColor
            }

        val textWidth =
            textPaint.measureText(block.name)

        val fontMetrics =
            textPaint.fontMetrics

        val textX =
            nodeWorldPos.x +
            (node.width - textWidth) / 2f

        val textY =
            nodeWorldPos.y +
            node.height / 2f -
            (fontMetrics.ascent + fontMetrics.descent) / 2f

        canvas.drawText(
            block.name,
            textX,
            textY,
            textPaint
        )

        drawPorts(
            canvas,
            node
        )

        for (childId in block.children) {
            val child =
                model!!.blocks.firstOrNull {
                    it.id == childId
                }

            if (child != null) {
                drawBlock(
                    canvas,
                    child,
                    drawn
                )
            }
        }
    }

    private fun drawBlocks(
        canvas: Canvas
    ) {
        val drawn =
            mutableSetOf<Int>()

        for (block in model!!.blocks) {
            if (block.parent == null) {
                drawBlock(
                    canvas,
                    block,
                    drawn
                )
            }
        }

        for (block in model!!.blocks) {
            if (!drawn.contains(block.id)) {
                drawBlock(
                    canvas,
                    block,
                    drawn
                )
            }
        }
    }

    private fun drawPorts(
        canvas: Canvas,
        node: Block
    ) {
        val isSource =
            linkingFrom?.model?.id == node.model!!.id

        val isDestination =
            hoveredDestination?.model?.id == node.model!!.id

        val nodeWorldPos = node.getWorldCoords()

        portPaint.color =
            if (isDestination) {
                getThemeColor(
                    androidx.appcompat.R.attr.colorAccent
                )
            } else {
                0xff555555.toInt()
            }

        canvas.drawCircle(
            nodeWorldPos.x,
            nodeWorldPos.y + node.height / 2f,
            portRadius,
            portPaint
        )

        portPaint.color =
            if (isSource) {
                getThemeColor(
                    androidx.appcompat.R.attr.colorAccent
                )
            } else {
                0xff555555.toInt()
            }

        canvas.drawCircle(
            nodeWorldPos.x + node.width,
            nodeWorldPos.y + node.height / 2f,
            portRadius,
            portPaint
        )
    }

    private fun drawLinks(
        canvas: Canvas
    ) {
        for (link in model!!.links) {
            val from = model!!.blocks.find {
                it.id == link.from
            }

            val to = model!!.blocks.find {
                it.id == link.to
            }

            if (from == null ||
                to == null
            ) {
                continue
            }

            val selected = 
                link.from == model!!.selectedLink?.from && 
                link.to == model!!.selectedLink?.to

            val paint =
                if (selected) {
                    selectedLinkPaint
                } else {
                    connectionPaint
                }

            paint.color =
                if (selected) {
                    0xff1976d2.toInt()
                } else {
                    getThemeColor(R.attr.colorAccentInactive)
                }

            drawLink(
                canvas,
                from.view!!,
                to.view!!,
                paint
            )
        }
    }

    private fun drawLink(
        canvas: Canvas,
        from: Block,
        to: Block,
        paint: Paint
    ) {
        val fromWorldPos = from.getWorldCoords()
        val toWorldPos = to.getWorldCoords()
        drawLinkCurve(
            canvas,
            fromWorldPos.x + from.width,
            fromWorldPos.y + from.height / 2f,
            toWorldPos.x,
            toWorldPos.y + to.height / 2f,
            paint
        )
    }

    private fun drawLinkPreview(
        canvas: Canvas
    ) {
        val from = linkingFrom
            ?: return

        linkPreviewPaint.color = getThemeColor(androidx.appcompat.R.attr.colorAccent)
        val fromWorldPos = from.getWorldCoords()

        drawLinkCurve(
            canvas,
            fromWorldPos.x + from.width,
            fromWorldPos.y + from.height / 2f,
            linkX,
            linkY,
            linkPreviewPaint
        )
    }

    private fun findBlock(
        screenX: Float,
        screenY: Float
    ): Block? {
        val point = screenToWorld(screenX, screenY)
        val x = point.first
        val y = point.second

        val candidates = mutableListOf<Block>()

        for (block in model!!.blocks) {
            val node = block.view!!

            val nodeWorldPos = node.getWorldCoords()

            if (
                x >= nodeWorldPos.x &&
                x <= nodeWorldPos.x + node.width &&
                y >= nodeWorldPos.y &&
                y <= nodeWorldPos.y + node.height
            ) {
                candidates.add(node)
            }
        }

        if (candidates.isEmpty()) {
            return null
        }

        var candidate =
            candidates.firstOrNull {
                it.model!!.parent == null
            } ?: candidates.first()

        var higherPriority = candidates.firstOrNull {
                it.model!!.parent == null && it.model!!.type != SimCustomSerialController.Block.Type.CONTAINER
            } ?: candidates.first()

        while (true) {
            val child =
                candidates.firstOrNull {
                    it.model!!.parent?.id == candidate.model!!.id
                } ?: break

            candidate = child
        }

        if ( higherPriority == null ) {
            return candidate
        } else {
            return higherPriority
        }
    }

    private fun findSourcePort(
        screenX: Float,
        screenY: Float
    ): Block? {
        val point =
            screenToWorld(
                screenX,
                screenY
            )

        val x = point.first
        val y = point.second

        for (block in model!!.blocks) {
            val node = block.view!!
            val nodeWorldPos = node.getWorldCoords()

            val port = Coordinates(nodeWorldPos.x + node.width, nodeWorldPos.y + node.height / 2f)

            if (distance(
                    x,
                    y,
                    port.x,
                    port.y
                ) <= portHitRadius
            ) {
                return node
            }
        }

        return null
    }

    private fun findDestinationPort(
        screenX: Float,
        screenY: Float
    ): Block? {
        val point =
            screenToWorld(
                screenX,
                screenY
            )

        val x = point.first
        val y = point.second

        for (block in model!!.blocks) {
            val node = block.view!!
            val nodeWorldPos = node.getWorldCoords()

            val port = Coordinates(nodeWorldPos.x, nodeWorldPos.y + node.height / 2f)

            if (distance(
                    x,
                    y,
                    port.x,
                    port.y
                ) <= portHitRadius
            ) {
                return node
            }
        }

        return null
    }

    private fun findLink(
        screenX: Float,
        screenY: Float
    ): Link? {
        val point =
            screenToWorld(
                screenX,
                screenY
            )

        val x = point.first
        val y = point.second

        for (link in model!!.links) {
            val from = model!!.blocks.find {
                it.id == link.from
            }

            val to = model!!.blocks.find {
                it.id == link.to
            }

            if (from == null ||
                to == null
            ) {
                continue
            }

            if (isPointNearLink(
                    x,
                    y,
                    from.view!!,
                    to.view!!
                )
            ) {
                return link.view!!
            }
        }

        return null
    }

    private fun isPointNearLink(
        x: Float,
        y: Float,
        from: Block,
        to: Block
    ): Boolean {
        val fromWorldPos = from.getWorldCoords()
        val toWorldPos = to.getWorldCoords()
        val startX =
            fromWorldPos.x + from.width

        val startY =
            fromWorldPos.y + from.height / 2f

        val endX =
            toWorldPos.x

        val endY =
            toWorldPos.y + to.height / 2f

        val controlDistance =
            max(
                40f,
                abs(endX - startX) * 0.5f
            )

        var previousX = startX
        var previousY = startY

        val steps = 30

        for (i in 1..steps) {
            val t =
                i.toFloat() / steps

            val inverse =
                1f - t

            val currentX =
                inverse * inverse * inverse * startX +
                3f * inverse * inverse * t *
                (startX + controlDistance) +
                3f * inverse * t * t *
                (endX - controlDistance) +
                t * t * t * endX

            val currentY =
                inverse * inverse * inverse * startY +
                3f * inverse * inverse * t *
                startY +
                3f * inverse * t * t *
                endY +
                t * t * t * endY

            if (distanceToSegment(
                    x,
                    y,
                    previousX,
                    previousY,
                    currentX,
                    currentY
                ) <= 18f
            ) {
                return true
            }

            previousX = currentX
            previousY = currentY
        }

        return false
    }

    private fun distanceToSegment(
        px: Float,
        py: Float,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float
    ): Float {
        val dx = x2 - x1
        val dy = y2 - y1

        if (dx == 0f &&
            dy == 0f
        ) {
            return distance(
                px,
                py,
                x1,
                y1
            )
        }

        val t = (
            (px - x1) * dx +
            (py - y1) * dy
        ) / (
            dx * dx +
            dy * dy
        )

        val clamped =
            t.coerceIn(
                0f,
                1f
            )

        val closestX =
            x1 + clamped * dx

        val closestY =
            y1 + clamped * dy

        return distance(
            px,
            py,
            closestX,
            closestY
        )
    }

    private fun distance(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float
    ): Float {
        val dx = x1 - x2
        val dy = y1 - y2

        return sqrt(
            dx * dx +
            dy * dy
        )
    }

    private fun containerGetUsableArea(
        container: Block
    ): RectF {
        return RectF(
            containerPadding,
            containerTitleHeight + containerPadding,
            container.width - containerPadding,
            container.height - containerPadding
        )
    }

    private fun moveBlock(
        node: Block,
        dx: Float,
        dy: Float
    ) {
        val worldDx = dx / scale
        val worldDy = dy / scale

        node.x += worldDx
        node.y += worldDy

        updateContainerMembership(node)

        invalidate()
    }

    private fun updateContainerMembership(
        node: Block
    ) {
        val currentContainer = node.model!!.parent

        var target: Block? = null

        for (container in model!!.blocks) {
            if (container.type !=
                SimCustomSerialController.Block.Type.CONTAINER
            ) {
                continue
            }

            if (container == currentContainer) {
                continue
            }

            if ( node == container ) {
                continue
            }

            updateContainerBounds(container.view!!)

            if (isBlockOverContainer(
                    node,
                    container.view!!
                )
            ) {
                target = container.view!!
                break
            }
        }

        if (target != null) {
            if (currentContainer != null) {
                model!!.onBlockExcluded(
                    currentContainer.view!!,
                    node
                )
                updateContainerBounds(
                    currentContainer.view!!
                )
            }

            val nodeWorldPos = node.getWorldCoords()
            val targetWorldPos = target.getWorldCoords()

            node.x =
                nodeWorldPos.x - targetWorldPos.x

            node.y =
                nodeWorldPos.y - targetWorldPos.y

            model!!.onBlockIncluded(
                target,
                node
            )

            updateContainerBounds(
                target
            )

            return
        }

        if (currentContainer != null &&
            !isBlockOverContainer(
                node,
                currentContainer.view!!
            )
        ) {
            model?.onBlockExcluded(
                currentContainer.view!!,
                node
            )

            updateContainerBounds(
                currentContainer.view!!
            )
        }
    }

    private fun isBlockOverContainer(
        node: Block,
        container: Block
    ): Boolean {
        if ( node == container ) {
            return false
        }
        val nodeWorldPos = node.getWorldCoords()
        val containerWorldPos = container.getWorldCoords()
        val centerX =
            nodeWorldPos.x + node.width / 2f

        val centerY =
            nodeWorldPos.y + node.height / 2f

        containerRect.set(
            containerWorldPos.x,
            containerWorldPos.y,
            containerWorldPos.x + container.width,
            containerWorldPos.y + container.height
        )

        val result = containerRect.contains(
            centerX,
            centerY
        )
        if ( result ) {
            logDebug("OVER CONTAINER node=${node.model!!.id} container=${container.model!!.id}")
        } else {
            logDebug("NOT OVER CONTAINER node=${node.model!!.id} (${centerX},${centerY}) container=${container.model!!.id} (${containerWorldPos.x},${containerWorldPos.y})")
        }
        return result
    }

    override fun onTouchEvent(
        event: MotionEvent
    ): Boolean {
        scaleDetector.onTouchEvent(event)

        val pointerCount = event.pointerCount

        if (pointerCount != lastPointerCount) {
            lastPointerCount = pointerCount

            lastX = event.x
            lastY = event.y

            if (pointerCount > 1) {
                draggingBlock = null
                movedDuringGesture = true
            }

            return true
        }

        if (linkingFrom != null) {
            when (event.actionMasked) {

                MotionEvent.ACTION_MOVE -> {
                    if ( !scaleDetector.isInProgress ) {
                        val point =
                            screenToWorld(
                                event.x,
                                event.y
                            )
    
                        linkX = point.first
                        linkY = point.second

                        hoveredDestination =
                            findDestinationPort(
                                event.x,
                                event.y
                            )
    
                        invalidate()
    
                        return true
                    }
                }

                MotionEvent.ACTION_UP -> {
                    val target =
                        findDestinationPort(
                            event.x,
                            event.y
                        )

                    val source =
                        linkingFrom

                    linkingFrom = null
                    hoveredDestination = null

                    if (source != null &&
                        target != null &&
                        source.model!!.id != target.model!!.id
                    ) {
                        model?.onLinkToBlock(
                            source,
                            target
                        )
                    }

                    invalidate()

                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    linkingFrom = null
                    hoveredDestination = null
                    invalidate()
                    return true
                }
            }

            return true
        }

        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                movedDuringGesture = false

                val node =
                    findBlock(
                        event.x,
                        event.y
                    )

                logDebug(
                    "DOWN x=${event.x} y=${event.y} block=${node?.model?.id} type=${node?.model?.type}"
                )

                draggingBlock = node

                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1) {
                    return true
                }
                val dx =
                    event.x - lastX

                val dy =
                    event.y - lastY

                logDebug(
                    "MOVE dx=$dx dy=$dy " +
                        "block=${draggingBlock?.model?.id} " +
                        "scaleInProgress=${scaleDetector.isInProgress}"
                )


                if (abs(dx) > 3f ||
                    abs(dy) > 3f
                ) {
                    movedDuringGesture = true
                }

                if (!scaleDetector.isInProgress) {
                    val node =
                        draggingBlock


                    if (node != null) {
                        moveBlock(
                            node,
                            dx,
                            dy
                        )

                        updateContainerMembership(
                            node
                        )
                    } else {
                        offsetX += dx
                        offsetY += dy
                        invalidate()
                    }

                    lastX = event.x
                    lastY = event.y
                }

                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                draggingBlock = null

                return true
            }
        }

        return true
    }

    fun resetView() {
        scale = 1f
        offsetX = 0f
        offsetY = 0f

        invalidate()
    }
}