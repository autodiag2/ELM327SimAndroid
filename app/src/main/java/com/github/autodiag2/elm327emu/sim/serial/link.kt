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

            val control1X: Float
            val control1Y: Float
            val control2X: Float
            val control2Y: Float

            if (endX < startX) {
                /*
                * Backward edge.
                *
                * Route below the blocks to produce a pronounced
                * U / half-circle-like curve instead of a straight line.
                */
                val curveOffset =
                    max(
                        100f,
                        abs(startX - endX) * 0.5f
                    )

                control1X = startX
                control1Y = startY + curveOffset

                control2X = endX
                control2Y = endY + curveOffset
            } else {
                /*
                * Normal forward edge.
                */
                val controlDistance =
                    max(
                        40f,
                        (endX - startX) * 0.5f
                    )

                control1X = startX + controlDistance
                control1Y = startY

                control2X = endX - controlDistance
                control2Y = endY
            }

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

            /*
            * Arrow at the middle of the Bézier curve.
            */
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
                3f * inverse * inverse *
                    (control1X - startX) +
                6f * inverse * t *
                    (control2X - control1X) +
                3f * t * t *
                    (endX - control2X)

            val tangentY =
                3f * inverse * inverse *
                    (control1Y - startY) +
                6f * inverse * t *
                    (control2Y - control1Y) +
                3f * t * t *
                    (endY - control2Y)

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

}