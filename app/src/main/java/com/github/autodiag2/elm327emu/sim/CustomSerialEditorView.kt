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
    }

    var listener: Listener? = null

    private val nodes = mutableListOf<Block>()
    private val connections = mutableListOf<Link>()

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

    private var selectedBlock: Block? = null
    private var selectedLink: Link? = null

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
                        selectedBlock = node
                        selectedLink = null

                        listener?.onBlockClicked(node)

                        invalidate()
                        return true
                    }

                    val connection = findLink(
                        event.x,
                        event.y
                    )

                    if (connection != null) {
                        selectedLink = connection
                        selectedBlock = null

                        invalidate()
                        return true
                    }

                    selectedBlock = null
                    selectedLink = null

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
                        selectedBlock = source
                        selectedLink = null

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
                        selectedBlock = node
                        selectedLink = null

                        invalidate()
                        return
                    }

                    val connection = findLink(
                        event.x,
                        event.y
                    )

                    if (connection != null) {
                        selectedLink = connection
                        selectedBlock = null

                        invalidate()
                        return
                    }

                    selectedBlock = null
                    selectedLink = null

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

    public fun isSomeSelection(): Boolean {
        return selectedBlock != null || selectedLink != null
    }

    fun setBlocks(
        value: List<Block>
    ) {
        nodes.clear()
        nodes.addAll(value)

        selectedBlock =
            selectedBlock?.let { selected ->
                nodes.find {
                    it.model!!.id == selected.model!!.id
                }
            }

        linkingFrom =
            linkingFrom?.let { source ->
                nodes.find {
                    it.model!!.id == source.model!!.id
                }
            }

        invalidate()
    }

    public fun onDelete() {
        if ( selectedBlock != null ) {
            removeBlock(selectedBlock!!.model!!.id)
            selectedBlock = null
        }
        if ( selectedLink != null ) {
            removeLink(selectedLink!!.from, selectedLink!!.to)
            selectedLink = null
        }
    }

    fun setLinks(
        value: List<Link>
    ) {
        connections.clear()
        connections.addAll(value)

        selectedLink =
            selectedLink?.let { selected ->
                connections.find {
                    it.from == selected.from &&
                    it.to == selected.to
                }
            }

        invalidate()
    }

    public fun rmLink(connection: Link) {
        connections.remove(connection)
        invalidate()
    }

    public fun addBlockChild(to: Block, child: Block) {
        assert(to.model!!.type == SimCustomSerialController.Block.Type.CONTAINER)
        to.children.add(child.model!!.id)
        invalidate()
    }
    
    public fun blockUpdate(block: SimCustomSerialController.Block) {
        val node = block.view as Block
        node.model!!.name = block.name
        invalidate()
    }

    public fun addBlock(
        model: SimCustomSerialController.Block
    ): Block {
        val node = Block(0f, 0f, model = model)
        nodes.add(node)
        invalidate()
        return node
    }

    fun removeBlock(
        id: Int
    ) {
        nodes.removeAll {
            it.model!!.id == id
        }

        for (node in nodes) {
            node.children.removeAll {
                it == id
            }
        }

        connections.removeAll {
            it.from == id ||
            it.to == id
        }

        if (selectedBlock?.model?.id == id) {
            selectedBlock = null
        }

        selectedLink =
            selectedLink?.takeUnless {
                it.from == id ||
                it.to == id
            }

        if (linkingFrom?.model?.id == id) {
            linkingFrom = null
        }

        invalidate()
    }

    fun addLink(
        from: Int,
        to: Int
    ) {
        if (from == to) {
            return
        }

        val fromBlock = nodes.find {
            it.model!!.id == from
        }

        val toBlock = nodes.find {
            it.model!!.id == to
        }

        if (fromBlock == null ||
            toBlock == null
        ) {
            return
        }

        connections.removeAll {
            it.from == from &&
            it.to == to
        }

        connections.add(
            Link(
                from = from,
                to = to
            )
        )

        invalidate()
    }

    fun removeLink(
        from: Int,
        to: Int
    ) {
        connections.removeAll {
            it.from == from &&
            it.to == to
        }

        if (selectedLink?.from == from &&
            selectedLink?.to == to
        ) {
            selectedLink = null
        }

        invalidate()
    }

    fun startLink(
        node: Block
    ) {
        linkingFrom = node
        selectedBlock = node
        selectedLink = null

        invalidate()
    }

    fun clearSelection() {
        selectedBlock = null
        selectedLink = null
        linkingFrom = null

        invalidate()
    }

    fun getSelectedBlock(): Block? {
        return selectedBlock
    }

    fun getSelectedLink(): Link? {
        return selectedLink
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
        for (node in nodes) {
            if (node.model!!.type ==
                SimCustomSerialController.Block.Type.CONTAINER
            ) {
                updateContainerBounds(node)
            }
        }
    }

    private fun updateContainerBounds(
        container: Block
    ) {
        val children =
            container.children.mapNotNull { childId ->
                nodes.find {
                    it.model!!.id == childId
                }
            }.filter {
                it.model!!.type !=
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

        for (child in children) {
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
        for (node in nodes) {
            if (node.model!!.type !=
                SimCustomSerialController.Block.Type.CONTAINER
            ) {
                continue
            }

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
                if (node == selectedBlock) {
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
                if (node == selectedBlock) {
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
        for (node in nodes) {
            if (node.model!!.type ==
                SimCustomSerialController.Block.Type.CONTAINER
            ) {
                continue
            }

            nodeRect.set(
                node.x,
                node.y,
                node.x + node.width,
                node.y + node.height
            )

            nodePaint.style =
                Paint.Style.FILL

            nodePaint.color =
                if (node == selectedBlock) {
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
                node.model!!.name,
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
        for (connection in connections) {
            val from = nodes.find {
                it.model!!.id == connection.from
            }

            val to = nodes.find {
                it.model!!.id == connection.to
            }

            if (from == null ||
                to == null
            ) {
                continue
            }

            val selected =
                selectedLink?.from ==
                    connection.from &&
                selectedLink?.to ==
                    connection.to

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
                from,
                to,
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

        for (i in nodes.indices.reversed()) {
            val node = nodes[i]

            if (node.model!!.type ==
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

        for (i in nodes.indices.reversed()) {
            val node = nodes[i]

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

        for (i in nodes.indices.reversed()) {
            val node = nodes[i]

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

        for (connection in connections) {
            val from = nodes.find {
                it.model!!.id == connection.from
            }

            val to = nodes.find {
                it.model!!.id == connection.to
            }

            if (from == null ||
                to == null
            ) {
                continue
            }

            if (isPointNearLink(
                    x,
                    y,
                    from,
                    to
                )
            ) {
                return connection
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
                    nodes.find {
                        it.model!!.id == childId
                    }

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

        var target: Block? = null

        for (container in nodes) {
            if (container.model!!.type !=
                SimCustomSerialController.Block.Type.CONTAINER
            ) {
                continue
            }

            if (container.children.contains(
                    node.model!!.id
                )
            ) {
                continue
            }

            updateContainerBounds(container)

            if (isBlockOverContainer(
                    node,
                    container
                )
            ) {
                target = container
                break
            }
        }

        if (target != null) {
            for (container in nodes) {
                if (container.model!!.type ==
                    SimCustomSerialController.Block.Type.CONTAINER
                ) {
                    container.children.remove(
                        node.model!!.id
                    )
                }
            }

            target.children.add(
                node.model!!.id
            )

            updateContainerBounds(target)

            return
        }

        val currentContainer =
            nodes.firstOrNull {
                it.model!!.type ==
                    SimCustomSerialController.Block.Type.CONTAINER &&
                it.children.contains(node.model!!.id)
            }

        if (currentContainer != null &&
            !isBlockOverContainer(
                node,
                currentContainer
            )
        ) {
            currentContainer.children.remove(
                node.model!!.id
            )

            updateContainerBounds(
                currentContainer
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

        updateContainerBounds(
            container
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
                        listener?.onLinkToBlock(
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