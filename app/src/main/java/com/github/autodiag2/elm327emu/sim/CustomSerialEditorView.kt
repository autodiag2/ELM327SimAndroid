package com.github.autodiag2.elm327emu.sim

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min
import android.util.AttributeSet

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
        var height: Float = 100f
    )

    data class Connection(
        val from: Int,
        val to: Int
    )

    interface Listener {
        fun onNodeClicked(node: Node)
        fun onNodeLongClicked(node: Node)
        fun onCreateLink(from: Node)
    }

    var listener: Listener? = null

    private val nodes = mutableListOf<Node>()
    private val connections = mutableListOf<Connection>()

    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val connectionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val portPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val nodeRect = RectF()
    private val gestureDetector: GestureDetector
    private val scaleDetector: ScaleGestureDetector

    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private var selectedNode: Node? = null
    private var draggingNode: Node? = null

    private var lastX = 0f
    private var lastY = 0f

    private var linkModeNode: Node? = null

    init {
        nodePaint.style = Paint.Style.FILL

        textPaint.textSize = 16f * resources.displayMetrics.scaledDensity

        connectionPaint.style = Paint.Style.STROKE
        connectionPaint.strokeWidth = 4f
        connectionPaint.strokeCap = Paint.Cap.ROUND

        portPaint.style = Paint.Style.FILL

        gestureDetector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {

                override fun onSingleTapUp(event: MotionEvent): Boolean {
                    val node = findNode(event.x, event.y)

                    if (node != null) {
                        if (linkModeNode != null &&
                            linkModeNode != node
                        ) {
                            listener?.onCreateLink(node)
                            linkModeNode = null
                            invalidate()
                            return true
                        }

                        selectedNode = node
                        listener?.onNodeClicked(node)
                        invalidate()
                    } else {
                        selectedNode = null
                        invalidate()
                    }

                    return true
                }

                override fun onDown(event: MotionEvent): Boolean {
                    return true
                }

                override fun onLongPress(event: MotionEvent) {
                    val node = findNode(event.x, event.y)

                    if (node != null) {
                        listener?.onNodeLongClicked(node)
                    }
                }
            }
        )

        scaleDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    scale *= detector.scaleFactor
                    scale = scale.coerceIn(0.35f, 2.5f)
                    invalidate()
                    return true
                }
            }
        )
    }

    fun setNodes(value: List<Node>) {
        nodes.clear()
        nodes.addAll(value)
        invalidate()
    }

    fun setConnections(value: List<Connection>) {
        connections.clear()
        connections.addAll(value)
        invalidate()
    }

    fun addNode(node: Node) {
        nodes.add(node)
        invalidate()
    }

    fun removeNode(id: Int) {
        nodes.removeAll { it.id == id }
        connections.removeAll {
            it.from == id || it.to == id
        }

        if (selectedNode?.id == id) {
            selectedNode = null
        }

        invalidate()
    }

    fun addConnection(from: Int, to: Int) {
        if (from == to) {
            return
        }

        connections.removeAll {
            it.from == from && it.to == to
        }

        connections.add(
            Connection(
                from = from,
                to = to
            )
        )

        invalidate()
    }

    fun removeConnection(from: Int, to: Int) {
        connections.removeAll {
            it.from == from && it.to == to
        }

        invalidate()
    }

    fun startLink(node: Node) {
        linkModeNode = node
        selectedNode = node
        invalidate()
    }

    fun clearSelection() {
        selectedNode = null
        linkModeNode = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.save()

        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)

        drawConnections(canvas)
        drawNodes(canvas)

        canvas.restore()
    }

    private fun drawNodes(canvas: Canvas) {
        for (node in nodes) {
            nodeRect.set(
                node.x,
                node.y,
                node.x + node.width,
                node.y + node.height
            )

            nodePaint.style = Paint.Style.FILL

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

            nodePaint.style = Paint.Style.STROKE
            nodePaint.strokeWidth = 3f
            nodePaint.color = 0xff444444.toInt()

            canvas.drawRoundRect(
                nodeRect,
                14f,
                14f,
                nodePaint
            )

            nodePaint.style = Paint.Style.FILL

            textPaint.color = 0xff202020.toInt()

            canvas.drawText(
                node.title,
                node.x + 16f,
                node.y + 32f,
                textPaint
            )

            drawPorts(canvas, node)
        }
    }

    private fun drawPorts(
        canvas: Canvas,
        node: Node
    ) {
        portPaint.color = 0xff555555.toInt()

        canvas.drawCircle(
            node.x,
            node.y + node.height / 2f,
            8f,
            portPaint
        )

        canvas.drawCircle(
            node.x + node.width,
            node.y + node.height / 2f,
            8f,
            portPaint
        )
    }

    private fun drawConnections(canvas: Canvas) {
        connectionPaint.color = 0xff555555.toInt()

        for (connection in connections) {
            val from = nodes.find {
                it.id == connection.from
            }

            val to = nodes.find {
                it.id == connection.to
            }

            if (from == null || to == null) {
                continue
            }

            val startX = from.x + from.width
            val startY = from.y + from.height / 2f

            val endX = to.x
            val endY = to.y + to.height / 2f

            val distance = max(
                40f,
                (endX - startX) * 0.5f
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
                connectionPaint
            )
        }
    }

    private fun findNode(
        screenX: Float,
        screenY: Float
    ): Node? {
        val x = (screenX - offsetX) / scale
        val y = (screenY - offsetY) / scale

        for (i in nodes.indices.reversed()) {
            val node = nodes[i]

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

    private fun moveNode(
        node: Node,
        dx: Float,
        dy: Float
    ) {
        node.x += dx / scale
        node.y += dy / scale
        invalidate()
    }

    override fun onTouchEvent(
        event: MotionEvent
    ): Boolean {

        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y

                draggingNode =
                    findNode(
                        event.x,
                        event.y
                    )

                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress) {
                    val node = draggingNode

                    if (node != null) {
                        val dx = event.x - lastX
                        val dy = event.y - lastY

                        moveNode(
                            node,
                            dx,
                            dy
                        )
                    } else {
                        offsetX += event.x - lastX
                        offsetY += event.y - lastY
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