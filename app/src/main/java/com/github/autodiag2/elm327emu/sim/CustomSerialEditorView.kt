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

class CustomSerialEditorView(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Node(
        val id: Int,
        var type: SimCustomSerialScreen.BlockType,
        var title: String,
        var x: Float,
        var y: Float,
        var width: Float = 260f,
        var height: Float = 100f,
        val children: MutableList<Int> = mutableListOf()
    )

    data class Connection(
        val from: Int,
        val to: Int
    )

    interface Listener {
        fun onNodeClicked(node: Node)
        fun onNodeLongClicked(node: Node)
        fun onCreateLink(from: Node)
        fun onLinkToNode(from: Node, to: Node)
    }

    var listener: Listener? = null

    private val nodes = mutableListOf<Node>()
    private val connections = mutableListOf<Connection>()

    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val containerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val connectionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectedConnectionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linkPreviewPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val portPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val nodeRect = RectF()
    private val containerRect = RectF()

    private val gestureDetector: GestureDetector
    private val scaleDetector: ScaleGestureDetector

    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private var selectedNode: Node? = null
    private var selectedConnection: Connection? = null

    private var draggingNode: Node? = null
    private var draggingContainer: Node? = null

    private var linkingFrom: Node? = null
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

        selectedConnectionPaint.style = Paint.Style.STROKE
        selectedConnectionPaint.strokeWidth = 7f
        selectedConnectionPaint.strokeCap = Paint.Cap.ROUND

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

                    val node = findNode(
                        event.x,
                        event.y
                    )

                    if (node != null) {
                        selectedNode = node
                        selectedConnection = null

                        listener?.onNodeClicked(node)

                        invalidate()
                        return true
                    }

                    val connection = findConnection(
                        event.x,
                        event.y
                    )

                    if (connection != null) {
                        selectedConnection = connection
                        selectedNode = null

                        invalidate()
                        return true
                    }

                    selectedNode = null
                    selectedConnection = null

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
                        selectedNode = source
                        selectedConnection = null

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

                    val node = findNode(
                        event.x,
                        event.y
                    )

                    if (node != null) {
                        selectedNode = node
                        selectedConnection = null

                        invalidate()
                        return
                    }

                    val connection = findConnection(
                        event.x,
                        event.y
                    )

                    if (connection != null) {
                        selectedConnection = connection
                        selectedNode = null

                        invalidate()
                        return
                    }

                    selectedNode = null
                    selectedConnection = null

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
        return selectedNode != null || selectedConnection != null
    }

    fun setNodes(
        value: List<Node>
    ) {
        nodes.clear()
        nodes.addAll(value)

        selectedNode =
            selectedNode?.let { selected ->
                nodes.find {
                    it.id == selected.id
                }
            }

        linkingFrom =
            linkingFrom?.let { source ->
                nodes.find {
                    it.id == source.id
                }
            }

        invalidate()
    }

    public fun onDelete() {
        if ( selectedNode != null ) {
            removeNode(selectedNode!!.id)
            selectedNode = null
        }
        if ( selectedConnection != null ) {
            removeConnection(selectedConnection!!.from, selectedConnection!!.to)
            selectedConnection = null
        }
    }

    fun setConnections(
        value: List<Connection>
    ) {
        connections.clear()
        connections.addAll(value)

        selectedConnection =
            selectedConnection?.let { selected ->
                connections.find {
                    it.from == selected.from &&
                    it.to == selected.to
                }
            }

        invalidate()
    }

    fun addNode(
        node: Node
    ) {
        nodes.add(node)
        invalidate()
    }

    fun removeNode(
        id: Int
    ) {
        nodes.removeAll {
            it.id == id
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

        if (selectedNode?.id == id) {
            selectedNode = null
        }

        selectedConnection =
            selectedConnection?.takeUnless {
                it.from == id ||
                it.to == id
            }

        if (linkingFrom?.id == id) {
            linkingFrom = null
        }

        invalidate()
    }

    fun addConnection(
        from: Int,
        to: Int
    ) {
        if (from == to) {
            return
        }

        val fromNode = nodes.find {
            it.id == from
        }

        val toNode = nodes.find {
            it.id == to
        }

        if (fromNode == null ||
            toNode == null
        ) {
            return
        }

        connections.removeAll {
            it.from == from &&
            it.to == to
        }

        connections.add(
            Connection(
                from = from,
                to = to
            )
        )

        invalidate()
    }

    fun removeConnection(
        from: Int,
        to: Int
    ) {
        connections.removeAll {
            it.from == from &&
            it.to == to
        }

        if (selectedConnection?.from == from &&
            selectedConnection?.to == to
        ) {
            selectedConnection = null
        }

        invalidate()
    }

    fun startLink(
        node: Node
    ) {
        linkingFrom = node
        selectedNode = node
        selectedConnection = null

        invalidate()
    }

    fun clearSelection() {
        selectedNode = null
        selectedConnection = null
        linkingFrom = null

        invalidate()
    }

    fun getSelectedNode(): Node? {
        return selectedNode
    }

    fun getSelectedConnection(): Connection? {
        return selectedConnection
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
        drawConnections(canvas)
        drawLinkPreview(canvas)
        drawNodes(canvas)

        canvas.restore()
    }

    private fun updateAllContainerBounds() {
        for (node in nodes) {
            if (node.type ==
                SimCustomSerialScreen.BlockType.CONTAINER
            ) {
                updateContainerBounds(node)
            }
        }
    }

    private fun updateContainerBounds(
        container: Node
    ) {
        val children =
            container.children.mapNotNull { childId ->
                nodes.find {
                    it.id == childId
                }
            }.filter {
                it.type !=
                    SimCustomSerialScreen.BlockType.CONTAINER
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
            if (node.type !=
                SimCustomSerialScreen.BlockType.CONTAINER
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
                if (node == selectedNode) {
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
                if (node == selectedNode) {
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
                node.title,
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

    private fun drawNodes(
        canvas: Canvas
    ) {
        for (node in nodes) {
            if (node.type ==
                SimCustomSerialScreen.BlockType.CONTAINER
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
                if (node == selectedNode) {
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
                node.title,
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
        node: Node
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

        if (linkingFrom?.id == node.id) {
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

    private fun drawConnections(
        canvas: Canvas
    ) {
        for (connection in connections) {
            val from = nodes.find {
                it.id == connection.from
            }

            val to = nodes.find {
                it.id == connection.to
            }

            if (from == null ||
                to == null
            ) {
                continue
            }

            val selected =
                selectedConnection?.from ==
                    connection.from &&
                selectedConnection?.to ==
                    connection.to

            val paint =
                if (selected) {
                    selectedConnectionPaint
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
        from: Node,
        to: Node,
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

    private fun findNode(
        screenX: Float,
        screenY: Float
    ): Node? {
        val point =
            screenToWorld(
                screenX,
                screenY
            )

        val x = point.first
        val y = point.second

        for (i in nodes.indices.reversed()) {
            val node = nodes[i]

            if (node.type ==
                SimCustomSerialScreen.BlockType.CONTAINER
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
    ): Node? {
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
    ): Node? {
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

    private fun findConnection(
        screenX: Float,
        screenY: Float
    ): Connection? {
        val point =
            screenToWorld(
                screenX,
                screenY
            )

        val x = point.first
        val y = point.second

        for (connection in connections) {
            val from = nodes.find {
                it.id == connection.from
            }

            val to = nodes.find {
                it.id == connection.to
            }

            if (from == null ||
                to == null
            ) {
                continue
            }

            if (isPointNearConnection(
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

    private fun isPointNearConnection(
        x: Float,
        y: Float,
        from: Node,
        to: Node
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

    private fun moveNode(
        node: Node,
        dx: Float,
        dy: Float
    ) {
        val worldDx =
            dx / scale

        val worldDy =
            dy / scale

        node.x += worldDx
        node.y += worldDy

        if (node.type ==
            SimCustomSerialScreen.BlockType.CONTAINER
        ) {
            for (childId in node.children) {
                val child =
                    nodes.find {
                        it.id == childId
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
        node: Node
    ) {
        if (node.type ==
            SimCustomSerialScreen.BlockType.CONTAINER
        ) {
            return
        }

        var target: Node? = null

        for (container in nodes) {
            if (container.type !=
                SimCustomSerialScreen.BlockType.CONTAINER
            ) {
                continue
            }

            if (container.children.contains(
                    node.id
                )
            ) {
                continue
            }

            updateContainerBounds(container)

            if (isNodeOverContainer(
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
                if (container.type ==
                    SimCustomSerialScreen.BlockType.CONTAINER
                ) {
                    container.children.remove(
                        node.id
                    )
                }
            }

            target.children.add(
                node.id
            )

            updateContainerBounds(target)

            return
        }

        val currentContainer =
            nodes.firstOrNull {
                it.type ==
                    SimCustomSerialScreen.BlockType.CONTAINER &&
                it.children.contains(node.id)
            }

        if (currentContainer != null &&
            !isNodeOverContainer(
                node,
                currentContainer
            )
        ) {
            currentContainer.children.remove(
                node.id
            )

            updateContainerBounds(
                currentContainer
            )
        }
    }

    private fun isNodeOverContainer(
        node: Node,
        container: Node
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
                        source.id != target.id
                    ) {
                        listener?.onLinkToNode(
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
                    findNode(
                        event.x,
                        event.y
                    )

                if (node?.type ==
                    SimCustomSerialScreen.BlockType.CONTAINER
                ) {
                    draggingContainer = node
                    draggingNode = null
                } else {
                    draggingNode = node
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
                        draggingNode

                    if (container != null) {
                        moveNode(
                            container,
                            dx,
                            dy
                        )
                    } else if (node != null) {
                        moveNode(
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
                draggingNode = null
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