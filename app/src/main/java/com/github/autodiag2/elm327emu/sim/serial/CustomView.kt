package com.github.autodiag2.elm327emu.sim.serial

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
import androidx.core.content.ContextCompat
import com.github.autodiag2.elm327emu.R
import android.util.TypedValue
import android.text.TextUtils
import android.text.TextPaint

class CustomView(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Coordinates(
        public var x: Float = 0f,
        public var y: Float = 0f
    )

    interface Listener {
        fun onBlockClicked(block: BlockView)
        fun onLinkToBlock(from: BlockView, to: BlockView)
        fun onBlockIncluded(parent: BlockView, child: BlockView)
        fun onBlockExcluded(parent: BlockView, child: BlockView)
        fun onElementSelected(element: ElementView<*>)
        fun onElementUnselected(element: ElementView<*>)
        fun onUnselectAll()
    }

    var model: CustomController? = null

    // --------- Customization settings ---------
    private var scale = 1f
    private val portHitRadius = 100f
    public val blockStandardContentPadding: Float = 40f
    private val autoPlacementMargin: Float = 80f
    private val linkSelectionSensitivity = 60f
    // --------- End Customization settings ---------

    private val linkPreviewPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val containerRect = RectF()

    private val gestureDetector: GestureDetector
    private val scaleDetector: ScaleGestureDetector

    private var offsetX = 0f
    private var offsetY = 0f

    private var draggingBlock: BlockView? = null
    public var linkingFrom: BlockView? = null
    public var hoveredDestination: BlockView? = null

    private var linkX = 0f
    private var linkY = 0f

    private var lastX = 0f
    private var lastY = 0f
    private var lastPointerCount = 0

    private var movedDuringGesture = false

    init {

        linkPreviewPaint.style = Paint.Style.STROKE
        linkPreviewPaint.strokeWidth = 5f
        linkPreviewPaint.strokeCap = Paint.Cap.ROUND

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

                    val block = findBlock(
                        event.x,
                        event.y
                    )

                    if (block != null) {
                        bringBlockToFront(block)
                        model?.toggleBlockSelection(block.model!!)
                        if (block.model!!.id in model!!.selectedBlocks) {
                            model?.onBlockClicked(block)
                        }

                        invalidate()
                        return true
                    }

                    val link = findLink(
                        event.x,
                        event.y
                    )

                    if (link != null) {
                        model?.toggleLinkSelection(link.model!!)

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

                    val link = findLink(
                        event.x,
                        event.y
                    )

                    if (link != null) {
                        model?.onElementSelected(link)

                        invalidate()
                        return
                    }

                    val block = findBlock(
                        event.x,
                        event.y
                    )

                    if (block != null) {
                        bringBlockToFront(block)
                        model?.onElementSelected(block)

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

    private fun bringBlockToFront(block: BlockView) {

        val blockModel = block.model ?: return
        val parent = blockModel.parent

        if (parent == null) {

            val index = model!!.blocks.indexOf(blockModel)

            if (index >= 0 && index != model!!.blocks.lastIndex) {
                model!!.blocks.removeAt(index)
                model!!.blocks.add(blockModel)
            }

        } else {

            val index = parent.children.indexOf(blockModel.id)

            if (index >= 0 && index != parent.children.lastIndex) {
                parent.children.removeAt(index)
                parent.children.add(blockModel.id)
            }

            parent.view?.let {
                bringBlockToFront(it)
            }
        }

        invalidate()
    }

    private fun updateContainerMembership(
        block: BlockView
    ) {
        val currentContainer = block.model!!.parent

        var target: BlockView? = null

        for (container in model!!.blocks) {
            if (container.type !=
                BlockController.Type.CONTAINER
            ) {
                continue
            }

            if (container == currentContainer) {
                continue
            }

            if ( block == container ) {
                continue
            }

            if (isDescendant(block, container.view!!)) {
                continue
            }

            if (isAncestor(container.view!!, block)) {
                continue
            }

            container.view!!.updateContainerBounds()

            if (isBlockOverContainer(
                    block,
                    container.view!!
                )
            ) {
                target = container.view!!
                break
            }
        }

        if (target != null) {
            val blockWorldPos = block.getWorldCoords()

            if (currentContainer != null) {
                model!!.onBlockExcluded(
                    currentContainer.view!!,
                    block
                )

                currentContainer.view!!.updateContainerBounds()
            }

            val targetWorldPos = target.getWorldCoords()

            block.x =
                blockWorldPos.x - targetWorldPos.x

            block.y =
                blockWorldPos.y - targetWorldPos.y

            model!!.onBlockIncluded(
                target,
                block
            )

            target.updateContainerBounds()

            return
        }

        if (
            currentContainer != null &&
            !isBlockOverContainer(
                block,
                currentContainer.view!!
            )
        ) {
            /*
            * block.x / block.y are currently relative to the
            * current container. Preserve the absolute position
            * before removing the parent.
            */
            val blockWorldPos =
                block.getWorldCoords()

            model?.onBlockExcluded(
                currentContainer.view!!,
                block
            )

            /*
            * The block is now a root block, so its local coordinates
            * are its world coordinates.
            */
            block.x =
                blockWorldPos.x

            block.y =
                blockWorldPos.y

            currentContainer.view!!.updateContainerBounds()
        }
    }

    private fun isBlockOverContainer(
        block: BlockView,
        container: BlockView
    ): Boolean {
        if ( block == container ) {
            return false
        }
        val blockWorldPos = block.getWorldCoords()
        val containerWorldPos = container.getWorldCoords()
        val centerX =
            blockWorldPos.x + block.width / 2f

        val centerY =
            blockWorldPos.y + block.height / 2f

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
            logDebug("OVER CONTAINER block=${block.model!!.id} container=${container.model!!.id}")
        } else {
            logDebug("NOT OVER CONTAINER block=${block.model!!.id} (${centerX},${centerY}) container=${container.model!!.id} (${containerWorldPos.x},${containerWorldPos.y})")
        }
        return result
    }

    public fun logDebug(message: String) {
        model!!.logDebug(message)
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

    private fun isAncestor(
        ancestor: BlockView,
        block: BlockView
    ): Boolean {
        var current = block.model?.parent

        while (current != null) {
            if (current === ancestor.model) {
                return true
            }

            current = current.parent
        }

        return false
    }

    private fun isDescendant(
        block: BlockView,
        possibleDescendant: BlockView
    ): Boolean {
        var current = possibleDescendant.model?.parent

        while (current != null) {
            if (current === block.model) {
                return true
            }

            current = current.parent
        }

        return false
    }

    public fun refresh() {
        invalidate()
    }

    public fun addBlock(
        model: BlockController
    ): BlockView {
        val block = BlockView(
            0f,
            0f,
            model = model,
            context = context,
            parentView = this
        )

        val worldCenterX =
            (width / 2f - offsetX) / scale

        val worldCenterY =
            (height / 2f - offsetY) / scale

        val strength = 1f

        val position =
            findFreeBlockPosition(
                block,
                worldCenterX,
                worldCenterY,
                strength
            )

        val parent = model.parent?.view
        if (parent == null) {
            block.x = position.x
            block.y = position.y
        } else {
            val parentWorldPos = parent.getWorldCoords()

            block.x =
                position.x - parentWorldPos.x

            block.y =
                position.y - parentWorldPos.y
        }

        return block
    }

    private fun findFreeBlockPosition(
        block: BlockView,
        centerX: Float,
        centerY: Float,
        strength: Float
    ): Coordinates {
        val minimumDistance = 20f
        val step = max(
            20f,
            min(block.width, block.height) * 0.25f
        )

        val maxRadius =
            max(width, height).toFloat() / scale

        var best =
            Coordinates(
                centerX - block.width / 2f,
                centerY - block.height / 2f
            )

        var bestScore = Float.MAX_VALUE

        var radius = 0f

        while (radius <= maxRadius) {
            val circumference =
                max(
                    1f,
                    2f * Math.PI.toFloat() * radius
                )

            val count =
                max(
                    1,
                    (circumference / step).toInt()
                )

            for (i in 0 until count) {
                val angle =
                    2f *
                    Math.PI.toFloat() *
                    i.toFloat() /
                    count.toFloat()

                val x =
                    centerX +
                    kotlin.math.cos(angle) * radius -
                    block.width / 2f

                val y =
                    centerY +
                    kotlin.math.sin(angle) * radius -
                    block.height / 2f

                val overlap =
                    blockOverlapScore(
                        x,
                        y,
                        block.width,
                        block.height,
                        strength
                    )

                val distance =
                    sqrt(
                        (x + block.width / 2f - centerX) *
                        (x + block.width / 2f - centerX) +
                        (y + block.height / 2f - centerY) *
                        (y + block.height / 2f - centerY)
                    )

                val score =
                    overlap * 100000f +
                    distance

                if (score < bestScore) {
                    bestScore = score

                    best =
                        Coordinates(
                            x,
                            y
                        )
                }

                if (overlap == 0f) {
                    return Coordinates(
                        x,
                        y
                    )
                }
            }

            radius += step
        }

        return best
    }

    private fun blockOverlapScore(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        strength: Float
    ): Float {
        var score = 0f

        for (other in model!!.blocks) {
            val block = other.view!!
            val otherPos = block.getWorldCoords()

            val otherLeft =
                otherPos.x - autoPlacementMargin

            val otherTop =
                otherPos.y - autoPlacementMargin

            val otherRight =
                otherPos.x +
                block.width +
                autoPlacementMargin

            val otherBottom =
                otherPos.y +
                block.height +
                autoPlacementMargin

            val overlapX =
                max(
                    0f,
                    min(
                        x + width,
                        otherRight
                    ) -
                    max(
                        x,
                        otherLeft
                    )
                )

            val overlapY =
                max(
                    0f,
                    min(
                        y + height,
                        otherBottom
                    ) -
                    max(
                        y,
                        otherTop
                    )
                )

            val overlap =
                overlapX * overlapY

            val area =
                width * height

            if (area > 0f) {
                score +=
                    (overlap / area) * strength
            }
        }

        return score
    }

    public fun addLink(model: LinkController): LinkView {
        return LinkView(model = model, context = context, parentView = this)
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
        if (hasInProgressBlock()) {
            postInvalidateDelayed(250L)
        }
    }

    private fun hasInProgressBlock(): Boolean {
        return model!!.blocks.any {
            model!!.getBlockState(it) ==
                StateMachine.BlockState.IN_PROGRESS
        }
    }

    private fun updateAllContainerBounds() {
        for (block in model!!.blocks) {
            if (block.type ==
                BlockController.Type.CONTAINER
            ) {
                block.view!!.updateContainerBounds()
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
                block.view!!.draw(canvas, drawn)
            }
        }

        for (block in model!!.blocks) {
            if (!drawn.contains(block.id)) {
                block.view!!.draw(canvas, drawn)
            }
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

            link.view!!.draw(canvas, from, to)
        }
    }

    private fun drawLinkPreview(
        canvas: Canvas
    ) {
        val from = linkingFrom
            ?: return

        linkPreviewPaint.color = getThemeColor(androidx.appcompat.R.attr.colorAccent)
        val fromWorldPos = from.getWorldCoords()

        LinkView.drawLinkCurve(
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
    ): BlockView? {
        val point = screenToWorld(screenX, screenY)
        val x = point.first
        val y = point.second

        val candidates = mutableListOf<BlockView>()

        for (block in model!!.blocks) {
            val blockView = block.view!!

            val blockWorldPos = blockView.getWorldCoords()

            if (
                x >= blockWorldPos.x &&
                x <= blockWorldPos.x + blockView.width &&
                y >= blockWorldPos.y &&
                y <= blockWorldPos.y + blockView.height
            ) {
                candidates.add(blockView)
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
                it.model!!.parent == null && it.model!!.type != BlockController.Type.CONTAINER
            }

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
    ): BlockView? {
        val point = screenToWorld(screenX, screenY)

        val x = point.first
        val y = point.second

        var closest: BlockView? = null
        var closestDistance = Float.MAX_VALUE

        for (block in model!!.blocks) {
            val blockView = block.view!!
            val blockWorldPos = blockView.getWorldCoords()

            val portX = blockWorldPos.x + blockView.width
            val portY = blockWorldPos.y + blockView.height / 2f

            val portDistance = distance(
                x,
                y,
                portX,
                portY
            )

            if (
                portDistance <= portHitRadius &&
                portDistance < closestDistance
            ) {
                closest = blockView
                closestDistance = portDistance
            }
        }

        return closest
    }

    private fun findDestinationPort(
        screenX: Float,
        screenY: Float
    ): BlockView? {
        val point = screenToWorld(screenX, screenY)

        val x = point.first
        val y = point.second

        var closest: BlockView? = null
        var closestDistance = Float.MAX_VALUE

        for (block in model!!.blocks) {
            val blockView = block.view!!
            val blockWorldPos = blockView.getWorldCoords()

            val portX = blockWorldPos.x
            val portY = blockWorldPos.y + blockView.height / 2f

            val portDistance = distance(
                x,
                y,
                portX,
                portY
            )

            if (
                portDistance <= portHitRadius &&
                portDistance < closestDistance
            ) {
                closest = blockView
                closestDistance = portDistance
            }
        }

        return closest
    }

    private fun findLink(
        screenX: Float,
        screenY: Float
    ): LinkView? {
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
        from: BlockView,
        to: BlockView
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
                ) <= linkSelectionSensitivity
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

    private fun moveBlock(
        block: BlockView,
        dx: Float,
        dy: Float
    ) {
        val worldDx = dx / scale
        val worldDy = dy / scale

        block.x += worldDx
        block.y += worldDy

        updateContainerMembership(block)

        invalidate()
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

                val block =
                    findBlock(
                        event.x,
                        event.y
                    )
                
                if ( block != null ) {
                    bringBlockToFront(block)
                }

                logDebug(
                    "DOWN x=${event.x} y=${event.y} block=${block?.model?.id} type=${block?.model?.type}"
                )

                draggingBlock = block

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
                    val block =
                        draggingBlock


                    if (block != null) {
                        moveBlock(
                            block,
                            dx,
                            dy
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