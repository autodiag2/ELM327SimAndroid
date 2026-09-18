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

class SimCustomSerialView(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    open class Block(
        var x: Float,
        var y: Float,
        var width: Float = 260f,
        var height: Float = 100f,
        val children: MutableList<Int> = mutableListOf(),
        model: SimCustomSerialController.Block? = null
    ) : SimCustomSerialController.ElementView<SimCustomSerialController.Block>(model = model)

    open class Link(
        val from: Int,
        val to: Int,
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

    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val containerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val connectionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectedLinkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linkPreviewPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val portPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val nodeRect = RectF()
    private val containerRect = RectF()

    private val gestureDetector: GestureDetector
    private val scaleDetector: ScaleGestureDetector

    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private var draggingBlock: Block? = null
    private var draggingContainer: Block? = null

    private var linkingFrom: Block? = null
    private var linkX = 0f
    private var linkY = 0f

    private var lastX = 0f
    private var lastY = 0f

    private var movedDuringGesture = false

    private val containerPadding = 32f
    private val containerTitleHeight = 40f
    private val portRadius = 8f
    private val portHitRadius = 28f

    init {
        nodePaint.style = Paint.Style.FILL
        containerPaint.style = Paint.Style.FILL

        textPaint.textSize =
            16f * resources.displayMetrics.scaledDensity

        connectionPaint.style = Paint.Style.STROKE
        connectionPaint.strokeWidth = 4f
        connectionPaint.strokeCap = Paint.Cap.ROUND

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
                        model?.onElementSelected(source)

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
                    scale *= detector.scaleFactor

                    scale = scale.coerceIn(
                        0.35f,
                        2.5f
                    )

                    invalidate()

                    return true
                }
            }
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

        drawContainers(canvas)
        drawLinks(canvas)
        drawLinkPreview(canvas)
        drawBlocks(canvas)

        canvas.restore()
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
            }.filter {
                it.type !=
                    SimCustomSerialController.Block.Type.CONTAINER
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

        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE

        for (childBlock in children) {
            val child = childBlock.view!!
            left = min(
                left,
                child.x
            )

            top = min(
                top,
                child.y
            )

            right = max(
                right,
                child.x + child.width
            )

            bottom = max(
                bottom,
                child.y + child.height
            )
        }

        container.x =
            left - containerPadding

        container.y =
            top -
            containerPadding -
            containerTitleHeight

        container.width =
            max(
                260f,
                right -
                    container.x +
                    containerPadding
            )

        container.height =
            max(
                140f,
                bottom -
                    container.y +
                    containerPadding
            )
    }

    private fun drawContainers(
        canvas: Canvas
    ) {
        for (block in model!!.blocks) {
            if (block.type !=
                SimCustomSerialController.Block.Type.CONTAINER
            ) {
                continue
            }

            val node = block.view!!
            updateContainerBounds(node)

            containerRect.set(
                node.x,
                node.y,
                node.x + node.width,
                node.y + node.height
            )

            containerPaint.style =
                Paint.Style.FILL

            containerPaint.color =
                if ( model!!.isBlockSelected(block) ) {
                    0x332196f3
                } else {
                    0x18000000
                }

            canvas.drawRoundRect(
                containerRect,
                18f,
                18f,
                containerPaint
            )

            containerPaint.style =
                Paint.Style.STROKE

            containerPaint.strokeWidth =
                if ( model!!.isBlockSelected(block) ) {
                    5f
                } else {
                    3f
                }

            containerPaint.color =
                0xff777777.toInt()

            canvas.drawRoundRect(
                containerRect,
                18f,
                18f,
                containerPaint
            )

            containerPaint.style =
                Paint.Style.FILL

            textPaint.color =
                0xff333333.toInt()

            canvas.drawText(
                node.model!!.name,
                node.x + 16f,
                node.y + 28f,
                textPaint
            )

            drawPorts(
                canvas,
                node
            )
        }
    }

    private fun drawBlocks(
        canvas: Canvas
    ) {
        for (block in model!!.blocks) {
            if (block.type ==
                SimCustomSerialController.Block.Type.CONTAINER
            ) {
                continue
            }

            val node = block.view!!
            nodeRect.set(
                node.x,
                node.y,
                node.x + node.width,
                node.y + node.height
            )

            nodePaint.style =
                Paint.Style.FILL

            nodePaint.color =
                if (model!!.isBlockSelected(block)) {
                    0xffd7e8ff.toInt()
                } else {
                    0xffeeeeee.toInt()
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
                0xff444444.toInt()

            canvas.drawRoundRect(
                nodeRect,
                14f,
                14f,
                nodePaint
            )

            nodePaint.style =
                Paint.Style.FILL

            textPaint.color =
                0xff202020.toInt()

            canvas.drawText(
                block.name,
                node.x + 16f,
                node.y + 32f,
                textPaint
            )

            drawPorts(
                canvas,
                node
            )
        }
    }

    private fun drawPorts(
        canvas: Canvas,
        node: Block
    ) {
        portPaint.color =
            0xff555555.toInt()

        canvas.drawCircle(
            node.x,
            node.y + node.height / 2f,
            portRadius,
            portPaint
        )

        canvas.drawCircle(
            node.x + node.width,
            node.y + node.height / 2f,
            portRadius,
            portPaint
        )

        if (linkingFrom?.model?.id == node.model!!.id) {
            portPaint.color =
                0xff1976d2.toInt()

            canvas.drawCircle(
                node.x + node.width,
                node.y + node.height / 2f,
                portRadius + 3f,
                portPaint
            )
        }
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
                    0xff555555.toInt()
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
        val startX =
            from.x + from.width

        val startY =
            from.y + from.height / 2f

        val endX =
            to.x

        val endY =
            to.y + to.height / 2f

        val distance =
            max(
                40f,
                abs(endX - startX) * 0.5f
            )

        val path = Path()

        path.moveTo(
            startX,
            startY
        )

        path.cubicTo(
            startX + distance,
            startY,
            endX - distance,
            endY,
            endX,
            endY
        )

        canvas.drawPath(
            path,
            paint
        )
    }

    private fun drawLinkPreview(
        canvas: Canvas
    ) {
        val from = linkingFrom
            ?: return

        val startX =
            from.x + from.width

        val startY =
            from.y + from.height / 2f

        val endX = linkX
        val endY = linkY

        val distance =
            max(
                40f,
                abs(endX - startX) * 0.5f
            )

        linkPreviewPaint.color =
            0xff1976d2.toInt()

        val path = Path()

        path.moveTo(
            startX,
            startY
        )

        path.cubicTo(
            startX + distance,
            startY,
            endX - distance,
            endY,
            endX,
            endY
        )

        canvas.drawPath(
            path,
            linkPreviewPaint
        )
    }

    private fun findBlock(
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

            if (block.type ==
                SimCustomSerialController.Block.Type.CONTAINER
            ) {
                updateContainerBounds(node)

                if (containerRect.contains(
                        x,
                        y
                    )
                ) {
                    return node
                }

                continue
            }

            if (x >= node.x &&
                x <= node.x + node.width &&
                y >= node.y &&
                y <= node.y + node.height
            ) {
                return node
            }
        }

        return null
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

            val portX =
                node.x + node.width

            val portY =
                node.y + node.height / 2f

            if (distance(
                    x,
                    y,
                    portX,
                    portY
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

            val portX =
                node.x

            val portY =
                node.y + node.height / 2f

            if (distance(
                    x,
                    y,
                    portX,
                    portY
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
        val startX =
            from.x + from.width

        val startY =
            from.y + from.height / 2f

        val endX =
            to.x

        val endY =
            to.y + to.height / 2f

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

    private fun screenToWorld(
        screenX: Float,
        screenY: Float
    ): Pair<Float, Float> {
        return Pair(
            (screenX - offsetX) / scale,
            (screenY - offsetY) / scale
        )
    }

    private fun moveBlock(
        node: Block,
        dx: Float,
        dy: Float
    ) {
        val worldDx =
            dx / scale

        val worldDy =
            dy / scale

        node.x += worldDx
        node.y += worldDy

        if (node.model!!.type ==
            SimCustomSerialController.Block.Type.CONTAINER
        ) {
            for (childId in node.children) {
                val child =
                    model!!.blocks.find {
                        it.id == childId
                    }?.view

                if (child != null) {
                    child.x += worldDx
                    child.y += worldDy
                }
            }
        }

        invalidate()
    }

    private fun updateContainerMembership(
        node: Block
    ) {
        if (node.model!!.type ==
            SimCustomSerialController.Block.Type.CONTAINER
        ) {
            return
        }

        val currentContainer =
            model!!.blocks.firstOrNull {
                it.type ==
                    SimCustomSerialController.Block.Type.CONTAINER &&
                it.children.contains(node.model!!.id)
            }

        var target: Block? = null

        for (container in model!!.blocks) {
            if (container.type !=
                SimCustomSerialController.Block.Type.CONTAINER
            ) {
                continue
            }

            if (container.view!! == currentContainer) {
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
            }

            model?.onBlockIncluded(
                target,
                node
            )

            updateContainerBounds(target)

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
        val centerX =
            node.x + node.width / 2f

        val centerY =
            node.y + node.height / 2f

        updateContainerBounds(container)

        containerRect.set(
            container.x,
            container.y,
            container.x + container.width,
            container.y + container.height
        )

        return containerRect.contains(
            centerX,
            centerY
        )
    }

    override fun onTouchEvent(
        event: MotionEvent
    ): Boolean {
        scaleDetector.onTouchEvent(event)

        if (linkingFrom != null) {
            when (event.actionMasked) {

                MotionEvent.ACTION_MOVE -> {
                    val point =
                        screenToWorld(
                            event.x,
                            event.y
                        )

                    linkX = point.first
                    linkY = point.second

                    invalidate()

                    return true
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

                if (node?.model?.type ==
                    SimCustomSerialController.Block.Type.CONTAINER
                ) {
                    draggingContainer = node
                    draggingBlock = null
                } else {
                    draggingBlock = node
                    draggingContainer = null
                }

                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx =
                    event.x - lastX

                val dy =
                    event.y - lastY

                if (abs(dx) > 3f ||
                    abs(dy) > 3f
                ) {
                    movedDuringGesture = true
                }

                if (!scaleDetector.isInProgress) {
                    val container =
                        draggingContainer

                    val node =
                        draggingBlock

                    if (container != null) {
                        moveBlock(
                            container,
                            dx,
                            dy
                        )
                    } else if (node != null) {
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
                }

                lastX = event.x
                lastY = event.y

                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                draggingBlock = null
                draggingContainer = null

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