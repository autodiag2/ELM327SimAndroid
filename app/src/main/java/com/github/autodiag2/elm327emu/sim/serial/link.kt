package com.github.autodiag2.elm327emu.sim.serial

import com.github.autodiag2.elm327emu.sim.serial.CustomView.Coordinates

// view imports
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.abs
import kotlin.math.max
import com.github.autodiag2.elm327emu.R
// end view imports

open class LinkController(
    val from: Int,
    val to: Int,
    view: LinkView? = null,
    id: Int? = null
) : ElementController<LinkView>(
    view = view,
    id = id
)

open class LinkView(
    context: Context,
    model: LinkController? = null,
    protected val parentView: CustomView
) : ElementView<LinkController>(model = model, context = context) {

    companion object {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val paintSelected = Paint(Paint.ANTI_ALIAS_FLAG)
        private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val arrowSize = 30f
        init {
            arrowPaint.style = Paint.Style.FILL
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f
            paint.strokeCap = Paint.Cap.ROUND

            paintSelected.style = Paint.Style.STROKE
            paintSelected.strokeWidth = 7f
            paintSelected.strokeCap = Paint.Cap.ROUND
        }
        private fun drawArrow(
            canvas: Canvas,
            x: Float,
            y: Float,
            angle: Float
        ) {

            val path = Path()

            path.moveTo(
                x + kotlin.math.cos(angle) * arrowSize,
                y + kotlin.math.sin(angle) * arrowSize
            )

            path.lineTo(
                x + kotlin.math.cos(angle + 2.5f) * arrowSize,
                y + kotlin.math.sin(angle + 2.5f) * arrowSize
            )

            path.lineTo(
                x + kotlin.math.cos(angle - 2.5f) * arrowSize,
                y + kotlin.math.sin(angle - 2.5f) * arrowSize
            )

            path.close()

            canvas.drawPath(
                path,
                arrowPaint
            )
        }
        public fun drawLinkCurve(
            canvas: Canvas,
            startX: Float,
            startY: Float,
            endX: Float,
            endY: Float,
            paint: Paint
        ) {
            val path = Path()

            val controlPoints = getCurveControlPoints(
                startX, startY, endX, endY
            )

            path.moveTo(
                startX,
                startY
            )

            path.cubicTo(
                controlPoints.control1X,
                controlPoints.control1Y,
                controlPoints.control2X,
                controlPoints.control2Y,
                endX,
                endY
            )

            canvas.drawPath(
                path,
                paint
            )

            /*
            * Arrow at the middle of the Bézier curve.
            */
            val t = 0.5f
            val inverse = 1f - t

            val arrowX =
                inverse * inverse * inverse * startX +
                3f * inverse * inverse * t * controlPoints.control1X +
                3f * inverse * t * t * controlPoints.control2X +
                t * t * t * endX

            val arrowY =
                inverse * inverse * inverse * startY +
                3f * inverse * inverse * t * controlPoints.control1Y +
                3f * inverse * t * t * controlPoints.control2Y +
                t * t * t * endY

            val tangentX =
                3f * inverse * inverse *
                    (controlPoints.control1X - startX) +
                6f * inverse * t *
                    (controlPoints.control2X - controlPoints.control1X) +
                3f * t * t *
                    (endX - controlPoints.control2X)

            val tangentY =
                3f * inverse * inverse *
                    (controlPoints.control1Y - startY) +
                6f * inverse * t *
                    (controlPoints.control2Y - controlPoints.control1Y) +
                3f * t * t *
                    (endY - controlPoints.control2Y)

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
                angle
            )
        }
        private data class CurveControlPoints(
            val control1X: Float,
            val control1Y: Float,
            val control2X: Float,
            val control2Y: Float
        )

        private fun getCurveControlPoints(
            startX: Float,
            startY: Float,
            endX: Float,
            endY: Float
        ): CurveControlPoints {
            return if (endX < startX) {
                val curveOffset = max(100f, abs(startX - endX) * 0.5f)

                CurveControlPoints(
                    startX,
                    startY + curveOffset,
                    endX,
                    endY + curveOffset
                )
            } else {
                val controlDistance = max(40f, (endX - startX) * 0.5f)

                CurveControlPoints(
                    startX + controlDistance,
                    startY,
                    endX - controlDistance,
                    endY
                )
            }
        }
    }

    private fun getCurvePoint(
        t: Float,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        controlPoints: CurveControlPoints
    ): Coordinates {
        val inverse = 1f - t

        return Coordinates(
            inverse * inverse * inverse * startX +
                3f * inverse * inverse * t * controlPoints.control1X +
                3f * inverse * t * t * controlPoints.control2X +
                t * t * t * endX,
            inverse * inverse * inverse * startY +
                3f * inverse * inverse * t * controlPoints.control1Y +
                3f * inverse * t * t * controlPoints.control2Y +
                t * t * t * endY
        )
    }
    // Temp section
    public fun isSelected(): Boolean {
        return parentView.model!!.isLinkSelected(model!!)
    }
    // End temp section

    public fun draw(canvas: Canvas, from: BlockController, to: BlockController) {
        val selected = isSelected()

        val paint =
            if (selected) {
                paintSelected
            } else {
                paint
            }

        paint.color =
            if (selected) {
                getThemeColor(
                    androidx.appcompat.R.attr.colorAccent
                )
            } else {
                getThemeColor(R.attr.colorAccentInactive)
            }

        val fromWorldPos = from.view!!.getWorldCoords()
        val toWorldPos = to.view!!.getWorldCoords()
        drawLinkCurve(
            canvas,
            fromWorldPos.x + from.view!!.width,
            fromWorldPos.y + from.view!!.height / 2f,
            toWorldPos.x,
            toWorldPos.y + to.view!!.height / 2f,
            paint
        )
    }

    public fun isPointNearLink(
        x: Float,
        y: Float,
        from: BlockView,
        to: BlockView,
        sensitivity: Float = 30f
    ): Boolean {
        val fromWorldPos = from.getWorldCoords()
        val toWorldPos = to.getWorldCoords()

        val startX = fromWorldPos.x + from.width
        val startY = fromWorldPos.y + from.height / 2f
        val endX = toWorldPos.x
        val endY = toWorldPos.y + to.height / 2f

        val controlPoints = getCurveControlPoints(
            startX,
            startY,
            endX,
            endY
        )

        var previous = Coordinates(startX, startY)

        for (i in 1..30) {
            val current = getCurvePoint(
                i / 30f,
                startX,
                startY,
                endX,
                endY,
                controlPoints
            )

            if (
                distanceToSegment(
                    x,
                    y,
                    previous.x,
                    previous.y,
                    current.x,
                    current.y
                ) <= sensitivity
            ) {
                return true
            }

            previous = current
        }

        return false
    }

    private fun distanceToSegment(
        x: Float,
        y: Float,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float
    ): Float {
        val dx = endX - startX
        val dy = endY - startY

        if (dx == 0f && dy == 0f) {
            return kotlin.math.sqrt(
                (x - startX) * (x - startX) +
                    (y - startY) * (y - startY)
            )
        }

        val t = (
            ((x - startX) * dx + (y - startY) * dy) /
                (dx * dx + dy * dy)
            ).coerceIn(0f, 1f)

        val closestX = startX + t * dx
        val closestY = startY + t * dy

        return kotlin.math.sqrt(
            (x - closestX) * (x - closestX) +
                (y - closestY) * (y - closestY)
        )
    }

}