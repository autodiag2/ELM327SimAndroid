package com.github.autodiag2.elm327emu.sim.serial

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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
        strokeWidth = dp(3f)
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }

    private val positionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }

    private var points = IntArray(0)

    private var timestamps = LongArray(0)

    private var playedEntries = 0

    private var replayStartTime = 0L

    private var replaySpeed = 1.0

    private var replayRunning = false

    private var indicatorPosition = 0f

    private val updateRunnable = object : Runnable {

        override fun run() {

            if (!replayRunning) {
                return
            }

            updateIndicator()

            if (replayRunning) {
                postDelayed(this, 16L)
            }
        }
    }

    init {

        val attributes = context.theme.obtainStyledAttributes(
            intArrayOf(android.R.attr.colorAccent)
        )

        val accentColor =
            attributes.getColor(
                0,
                0xff009688.toInt()
            )

        attributes.recycle()

        curvePaint.color = accentColor
        borderPaint.color = accentColor
        positionPaint.color = accentColor
        axisPaint.color = accentColor
    }

    fun setEntries(entries: List<LogEntry>) {

        points = IntArray(entries.size)

        timestamps = LongArray(entries.size)

        var count = 0

        entries.forEachIndexed { index, entry ->

            timestamps[index] = entry.ts

            if (
                entry.type == LogEntryType.SENT ||
                entry.type == LogEntryType.RECV
            ) {
                count++
            }

            points[index] = count
        }

        playedEntries = 0

        indicatorPosition = 0f

        replayRunning = false

        removeCallbacks(updateRunnable)

        invalidate()
    }

    fun setPlayedEntries(count: Int) {

        playedEntries =
            count.coerceIn(
                0,
                points.size
            )

        if (!replayRunning) {

            indicatorPosition =
                if (points.isEmpty()) {
                    0f
                } else {
                    (playedEntries - 1)
                        .coerceIn(
                            0,
                            points.lastIndex
                        )
                        .toFloat()
                }
        }

        invalidate()
    }

    fun startPlayback(speed: Double) {

        if (points.isEmpty()) {
            return
        }

        replaySpeed =
            if (
                speed.isFinite() &&
                speed > 0.0
            ) {
                speed
            } else {
                1.0
            }

        replayStartTime =
            System.currentTimeMillis()

        replayRunning = true

        removeCallbacks(updateRunnable)

        post(updateRunnable)

        invalidate()
    }

    fun stopPlayback() {

        replayRunning = false

        removeCallbacks(updateRunnable)

        invalidate()
    }

    private fun updateIndicator() {

        if (timestamps.isEmpty()) {

            indicatorPosition = 0f

            invalidate()

            return
        }

        if (timestamps.size == 1) {

            indicatorPosition = 0f

            invalidate()

            return
        }

        val elapsed =
            (
                System.currentTimeMillis() -
                    replayStartTime
                ).coerceAtLeast(0L)

        val replayElapsed =
            elapsed * replaySpeed

        val firstTimestamp =
            timestamps.first()

        val targetTimestamp =
            firstTimestamp +
                replayElapsed.toLong()

        if (targetTimestamp >= timestamps.last()) {

            indicatorPosition =
                timestamps.lastIndex.toFloat()

            invalidate()

            return
        }

        var index = 0

        while (
            index < timestamps.lastIndex &&
            timestamps[index + 1] <= targetTimestamp
        ) {
            index++
        }

        val t1 =
            timestamps[index]

        val t2 =
            timestamps[index + 1]

        indicatorPosition =
            if (t2 <= t1) {
                index.toFloat()
            } else {
                index +
                    (
                        targetTimestamp - t1
                    ).toFloat() /
                    (
                        t2 - t1
                    ).toFloat()
            }

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {

        super.onDraw(canvas)

        if (points.isEmpty()) {
            return
        }

        val borderWidth = dp(2f)

        val left =
            paddingLeft.toFloat() +
                borderWidth

        val right =
            width.toFloat() -
                paddingRight.toFloat() -
                borderWidth

        val top =
            paddingTop.toFloat() +
                borderWidth

        val bottom =
            height.toFloat() -
                paddingBottom.toFloat() -
                borderWidth

        val chartWidth =
            right - left

        val chartHeight =
            bottom - top

        if (
            chartWidth <= 0f ||
            chartHeight <= 0f
        ) {
            return
        }

        val maxValue =
            points.maxOrNull() ?: 0

        if (maxValue <= 0) {
            return
        }

        val firstTimestamp =
            timestamps.first()

        val lastTimestamp =
            timestamps.last()

        val timestampRange =
            lastTimestamp - firstTimestamp

        val path = Path()

        points.forEachIndexed { index, value ->

            val x =
                if (
                    timestampRange <= 0L
                ) {
                    left
                } else {
                    left +
                        chartWidth *
                        (
                            timestamps[index] -
                                firstTimestamp
                        ).toFloat() /
                        timestampRange.toFloat()
                }

            val y =
                bottom -
                    chartHeight *
                    value /
                    maxValue.toFloat()

            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        axisPaint.alpha = 70

        canvas.drawLine(
            left,
            bottom,
            right,
            bottom,
            axisPaint
        )

        canvas.drawLine(
            left,
            top,
            left,
            bottom,
            axisPaint
        )

        val indicatorX =
            if (points.size == 1) {
                left
            } else if (timestampRange <= 0L) {
                left
            } else {
                val elapsed =
                    if (replayRunning) {
                        (
                            System.currentTimeMillis() -
                                replayStartTime
                            ).coerceAtLeast(0L) *
                            replaySpeed
                    } else {
                        val index =
                            indicatorPosition
                                .coerceIn(
                                    0f,
                                    points.lastIndex.toFloat()
                                )

                        val lower =
                            index.toInt()

                        val upper =
                            (lower + 1)
                                .coerceAtMost(
                                    points.lastIndex
                                )

                        if (lower == upper) {
                            (
                                timestamps[lower] -
                                    firstTimestamp
                            ).toDouble()
                        } else {
                            val fraction =
                                index - lower

                            val t1 =
                                timestamps[lower]

                            val t2 =
                                timestamps[upper]

                            t1 +
                                (
                                    t2 - t1
                                ) * fraction
                        }
                    }

                left +
                    chartWidth *
                    elapsed.toFloat() /
                    timestampRange.toFloat()
            }

        val clippedIndicatorX =
            indicatorX.coerceIn(
                left,
                right
            )

        canvas.save()

        curvePaint.alpha = 55

        canvas.drawPath(
            path,
            curvePaint
        )

        canvas.clipRect(
            left,
            top,
            clippedIndicatorX,
            bottom
        )

        curvePaint.alpha = 255

        canvas.drawPath(
            path,
            curvePaint
        )

        canvas.restore()

        positionPaint.alpha = 220

        canvas.drawLine(
            clippedIndicatorX,
            top,
            clippedIndicatorX,
            bottom,
            positionPaint
        )

        borderPaint.alpha = 255

        val borderRect =
            RectF(
                borderWidth,
                borderWidth,
                width.toFloat() -
                    borderWidth,
                height.toFloat() -
                    borderWidth
            )

        canvas.drawRect(
            borderRect,
            borderPaint
        )
    }

    override fun onDetachedFromWindow() {

        replayRunning = false

        removeCallbacks(updateRunnable)

        super.onDetachedFromWindow()
    }

    private fun dp(value: Float): Float {

        return value *
            resources.displayMetrics.density
    }
}