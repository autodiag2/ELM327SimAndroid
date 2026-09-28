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
import com.github.autodiag2.elm327emu.R

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

    private var rates = FloatArray(0)
    private var timestamps = LongArray(0)

    private var playedEntries = 0

    private var replayStartTime = 0L

    private var replaySpeed = 1.0

    private var replayRunning = false

    private var indicatorPosition = 0f

    /**
     * Exponential decay time in milliseconds.
     *
     * 1000 ms means that the interaction activity falls
     * to about 37% of its value after one second without
     * another interaction.
     */
    var decayTimeMs = 100.0
        set(value) {
            field = value.coerceAtLeast(1.0)
        }

    private var colorAccentInProgress = 0
    private var colorAccentSuccess = 0
    private var colorAccent = 0

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
            intArrayOf(
                R.attr.colorAccentInProgress,
                R.attr.colorAccentSuccess,
                android.R.attr.textColor,
                android.R.attr.colorAccent
            )
        )

        colorAccentInProgress =
            attributes.getColor(
                0,
                0xff009688.toInt()
            )

        colorAccentSuccess =
            attributes.getColor(
                1,
                0xff009688.toInt()
            )

        val textColor =
            attributes.getColor(
                2,
                0xff000000.toInt()
            )

        colorAccent =
            attributes.getColor(
                3,
                0xff009688.toInt()
            )

        attributes.recycle()

        curvePaint.color = colorAccentInProgress
        borderPaint.color = textColor
        positionPaint.color = colorAccent
        axisPaint.color = textColor
    }

    fun setEntries(entries: List<LogEntry>) {

        timestamps =
            if (entries.isEmpty()) {
                LongArray(0)
            } else {
                entries.map { it.ts }.toLongArray()
            }

        rates =
            FloatArray(timestamps.size)

        /*
         * The rate is an exponentially decaying interaction
         * activity measured in interactions/second.
         *
         * Every SENT/RECV interaction adds:
         *
         *     1000 / decayTimeMs
         *
         * interactions/second.
         *
         * Between interactions:
         *
         *     rate(t) = rate(t0) * exp(-dt / decayTime)
         */
        var activity = 0.0

        for (index in timestamps.indices) {

            if (index > 0) {

                val elapsed =
                    (
                        timestamps[index] -
                            timestamps[index - 1]
                        ).coerceAtLeast(0L)

                activity *=
                    Math.exp(
                        -elapsed /
                            decayTimeMs
                    )
            }

            if (
                entries[index].type ==
                    LogEntryType.SENT ||
                entries[index].type ==
                    LogEntryType.RECV
            ) {

                activity +=
                    1000.0 /
                        decayTimeMs
            }

            rates[index] =
                activity.toFloat()
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
                timestamps.size
            )

        if (!replayRunning) {

            indicatorPosition =
                if (timestamps.isEmpty()) {
                    0f
                } else {
                    (playedEntries - 1)
                        .coerceIn(
                            0,
                            timestamps.lastIndex
                        )
                        .toFloat()
                }
        }

        invalidate()
    }

    fun startPlayback(speed: Double) {

        if (timestamps.isEmpty()) {
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

    private fun calculateMonotonicTangents(
        x: FloatArray,
        y: FloatArray
    ): FloatArray {

        val count = x.size

        val tangents =
            FloatArray(count)

        if (count < 2) {
            return tangents
        }

        val slopes =
            FloatArray(count - 1)

        for (index in 0 until count - 1) {

            val dx =
                x[index + 1] -
                    x[index]

            slopes[index] =
                if (dx <= 0f) {
                    0f
                } else {
                    (
                        y[index + 1] -
                            y[index]
                        ) / dx
                }
        }

        tangents[0] =
            slopes[0]

        tangents[count - 1] =
            slopes[count - 2]

        for (index in 1 until count - 1) {

            val previousSlope =
                slopes[index - 1]

            val nextSlope =
                slopes[index]

            if (
                previousSlope == 0f ||
                nextSlope == 0f ||
                previousSlope * nextSlope < 0f
            ) {

                tangents[index] = 0f

            } else {

                tangents[index] =
                    (
                        previousSlope +
                            nextSlope
                        ) / 2f
            }
        }

        for (index in 0 until count - 1) {

            val slope =
                slopes[index]

            if (slope == 0f) {

                tangents[index] = 0f
                tangents[index + 1] = 0f

                continue
            }

            val a =
                tangents[index] /
                    slope

            val b =
                tangents[index + 1] /
                    slope

            val magnitude =
                a * a +
                    b * b

            if (magnitude > 9f) {

                val scale =
                    3f /
                        Math.sqrt(
                            magnitude.toDouble()
                        ).toFloat()

                tangents[index] =
                    scale *
                        a *
                        slope

                tangents[index + 1] =
                    scale *
                        b *
                        slope
            }
        }

        return tangents
    }

    override fun onDraw(canvas: Canvas) {

        super.onDraw(canvas)

        if (rates.isEmpty()) {
            return
        }

        val borderWidth = dp(2f)
        val curveHalfWidth = dp(3f) / 2f

        val left = borderWidth + curveHalfWidth
        val right = width.toFloat() - borderWidth - curveHalfWidth
        val top = borderWidth + curveHalfWidth
        val bottom = height.toFloat() - borderWidth - curveHalfWidth

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
            rates.maxOrNull() ?: 0f

        if (maxValue <= 0f) {
            return
        }

        val firstTimestamp =
            timestamps.first()

        val lastTimestamp =
            timestamps.last()

        val timestampRange =
            lastTimestamp -
                firstTimestamp

        val curveX =
            FloatArray(rates.size)

        val curveY =
            FloatArray(rates.size)

        rates.forEachIndexed { index, rate ->

            curveX[index] =
                if (timestampRange <= 0L) {
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

            curveY[index] =
                bottom -
                    chartHeight *
                    rate /
                    maxValue
        }

        val path = Path()

        path.moveTo(
            curveX[0],
            curveY[0]
        )

        if (rates.size > 1) {

            val tangents =
                calculateMonotonicTangents(
                    curveX,
                    curveY
                )

            for (
                index in
                    0 until rates.lastIndex
            ) {

                val x1 =
                    curveX[index]

                val y1 =
                    curveY[index]

                val x2 =
                    curveX[index + 1]

                val y2 =
                    curveY[index + 1]

                val dx =
                    x2 - x1

                if (dx <= 0f) {

                    path.lineTo(
                        x2,
                        y2
                    )

                    continue
                }

                val control1X =
                    x1 +
                        dx / 3f

                val control1Y =
                    y1 +
                        tangents[index] *
                        dx / 3f

                val control2X =
                    x2 -
                        dx / 3f

                val control2Y =
                    y2 -
                        tangents[index + 1] *
                        dx / 3f

                path.cubicTo(
                    control1X,
                    control1Y,
                    control2X,
                    control2Y,
                    x2,
                    y2
                )
            }
        }

        val indicatorX =
            if (timestamps.size == 1) {

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
                            indicatorPosition.coerceIn(
                                0f,
                                timestamps.lastIndex.toFloat()
                            )

                        val lower =
                            index.toInt()

                        val upper =
                            (lower + 1).coerceAtMost(
                                timestamps.lastIndex
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

        curvePaint.alpha = 255
        curvePaint.color = colorAccentInProgress

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

        curvePaint.color = colorAccentSuccess

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

        val borderHalfWidth = dp(2f) / 2f

        val borderRect =
            RectF(
                borderHalfWidth,
                borderHalfWidth,
                width.toFloat() - borderHalfWidth,
                height.toFloat() - borderHalfWidth
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