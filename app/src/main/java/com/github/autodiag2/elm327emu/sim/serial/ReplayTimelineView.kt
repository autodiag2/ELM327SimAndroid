package com.github.autodiag2.elm327emu.sim.serial

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.github.autodiag2.elm327emu.LogEntry
import com.github.autodiag2.elm327emu.LogEntryType

class ReplayTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private val positionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private var points = IntArray(0)
    private var playedEntries = 0

    fun setEntries(entries: List<LogEntry>) {
        points = IntArray(entries.size)

        var count = 0

        entries.forEachIndexed { index, entry ->
            if (
                entry.type == LogEntryType.SENT ||
                entry.type == LogEntryType.RECV
            ) {
                count++
            }

            points[index] = count
        }

        playedEntries = 0
        invalidate()
    }

    fun setPlayedEntries(count: Int) {
        playedEntries = count.coerceIn(0, points.size)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (points.isEmpty()) {
            return
        }

        val left = paddingLeft.toFloat()
        val right = width - paddingRight.toFloat()
        val top = paddingTop.toFloat()
        val bottom = height - paddingBottom.toFloat()

        val chartWidth = right - left
        val chartHeight = bottom - top

        if (chartWidth <= 0f || chartHeight <= 0f) {
            return
        }

        val maxValue = points.maxOrNull() ?: 0

        if (maxValue <= 0) {
            return
        }

        axisPaint.alpha = 100
        canvas.drawLine(left, bottom, right, bottom, axisPaint)
        canvas.drawLine(left, top, left, bottom, axisPaint)

        val path = Path()

        points.forEachIndexed { index, value ->
            val x =
                if (points.size == 1) {
                    left
                } else {
                    left + chartWidth * index / (points.size - 1)
                }

            val y =
                bottom -
                    chartHeight * value / maxValue.toFloat()

            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        val playedIndex =
            (playedEntries - 1).coerceIn(0, points.lastIndex)

        val playedX =
            if (points.size == 1) {
                left
            } else {
                left + chartWidth * playedIndex / (points.size - 1)
            }

        canvas.save()

        curvePaint.alpha = 55
        canvas.drawPath(path, curvePaint)

        canvas.clipRect(left, top, playedX, bottom)

        curvePaint.alpha = 255
        canvas.drawPath(path, curvePaint)

        canvas.restore()

        positionPaint.alpha = 180
        canvas.drawLine(
            playedX,
            top,
            playedX,
            bottom,
            positionPaint
        )
    }
}