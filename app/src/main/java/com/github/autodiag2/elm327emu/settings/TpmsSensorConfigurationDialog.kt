package com.github.autodiag2.elm327emu.settings

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.InputFilter
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import com.github.autodiag2.elm327emu.R
import com.github.autodiag2.elm327emu.com.tpms.TpmsSensorConfiguration
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

class TpmsSensorConfigurationDialog(
    private val context: Context,
    private val preferences: SharedPreferences,
    private val onSaved: () -> Unit
) {
    fun show() {
        val ids = TpmsSensorConfiguration.positions.associateWith { position ->
            preferences.getString(
                TpmsSensorConfiguration.preferenceKey(position),
                TpmsSensorConfiguration.defaultId(position)
            )?.let { TpmsSensorConfiguration.normalizeId(it) }
                ?: TpmsSensorConfiguration.defaultId(position)
        }.toMutableMap()

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        val hint = TextView(context).apply {
            text = context.getString(R.string.tpms_sensor_configure_hint)
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }
        content.addView(hint, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        lateinit var carView: TpmsSensorCarView
        carView = TpmsSensorCarView(context, ids) { position ->
            editSensorId(position, ids, carView)
        }
        content.addView(carView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val scrollView = ScrollView(context).apply {
            isFillViewport = true
            addView(content)
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.tpms_sensor_configure_dialog_title)
            .setView(scrollView)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val editor = preferences.edit()
                TpmsSensorConfiguration.positions.forEach { position ->
                    editor.putString(
                        TpmsSensorConfiguration.preferenceKey(position),
                        ids.getValue(position)
                    )
                }
                editor.apply()
                onSaved()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun editSensorId(
        position: String,
        ids: MutableMap<String, String>,
        carView: TpmsSensorCarView
    ) {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(InputFilter.LengthFilter(6))
            setSingleLine(true)
            setText(ids.getValue(position))
            selectAll()
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.tpms_sensor_id_title, position))
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val id = TpmsSensorConfiguration.normalizeId(input.text.toString())
                if (id == null) {
                    input.error = context.getString(R.string.tpms_sensor_id_invalid)
                } else {
                    ids[position] = id
                    carView.invalidate()
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()
}

private class TpmsSensorCarView(
    context: Context,
    private val ids: Map<String, String>,
    private val onSensorClick: (String) -> Unit
) : View(context) {
    private val density = resources.displayMetrics.density
    private val accentColor: Int
    private val primaryColor: Int
    private val secondaryColor: Int
    private val surfaceColor: Int
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val idPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD)
    }
    private val touchPoints = linkedMapOf(
        "FL" to (59f to 91f),
        "FR" to (241f to 91f),
        "RL" to (59f to 245f),
        "RR" to (241f to 245f),
        "SP" to (150f to 316f)
    )

    init {
        val attributes = context.theme.obtainStyledAttributes(
            intArrayOf(
                android.R.attr.colorAccent,
                android.R.attr.textColorPrimary,
                android.R.attr.textColorSecondary,
                android.R.attr.colorBackground
            )
        )
        accentColor = attributes.getColor(0, 0xFF4CAF50.toInt())
        primaryColor = attributes.getColor(1, Color.WHITE)
        secondaryColor = attributes.getColor(2, Color.LTGRAY)
        surfaceColor = attributes.getColor(3, Color.DKGRAY)
        attributes.recycle()
        isClickable = true
        contentDescription = context.getString(R.string.tpms_sensor_configure_dialog_title)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = (300 * density).roundToInt()
        val width = resolveSize(desiredWidth, widthMeasureSpec)
        val desiredHeight = (width * 1.2f).roundToInt()
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = min(width / 300f, height / 360f)
        val left = (width - 300f * scale) / 2f
        val top = (height - 360f * scale) / 2f
        canvas.save()
        canvas.translate(left, top)
        canvas.scale(scale, scale)
        drawCar(canvas)
        drawWheel(canvas, "FL", 59f, 91f)
        drawWheel(canvas, "FR", 241f, 91f)
        drawWheel(canvas, "RL", 59f, 245f)
        drawWheel(canvas, "RR", 241f, 245f)
        drawSpare(canvas)
        canvas.restore()
    }

    private fun drawCar(canvas: Canvas) {
        fillPaint.color = ColorUtils.setAlphaComponent(surfaceColor, 72)
        strokePaint.color = primaryColor
        strokePaint.strokeWidth = 2.5f
        val body = Path().apply {
            moveTo(119f, 20f)
            cubicTo(101f, 25f, 91f, 43f, 84f, 68f)
            lineTo(74f, 108f)
            lineTo(74f, 260f)
            cubicTo(76f, 292f, 91f, 319f, 113f, 331f)
            cubicTo(132f, 340f, 168f, 340f, 187f, 331f)
            cubicTo(209f, 319f, 224f, 292f, 226f, 260f)
            lineTo(226f, 108f)
            lineTo(216f, 68f)
            cubicTo(209f, 43f, 199f, 25f, 181f, 20f)
            close()
        }
        canvas.drawPath(body, fillPaint)
        canvas.drawPath(body, strokePaint)

        fillPaint.color = ColorUtils.setAlphaComponent(accentColor, 56)
        strokePaint.color = secondaryColor
        strokePaint.strokeWidth = 1.5f
        val windshield = Path().apply {
            moveTo(105f, 66f)
            lineTo(195f, 66f)
            lineTo(207f, 106f)
            lineTo(93f, 106f)
            close()
        }
        canvas.drawPath(windshield, fillPaint)
        canvas.drawPath(windshield, strokePaint)

        val rearWindow = Path().apply {
            moveTo(93f, 255f)
            lineTo(207f, 255f)
            lineTo(192f, 293f)
            lineTo(108f, 293f)
            close()
        }
        canvas.drawPath(rearWindow, fillPaint)
        canvas.drawPath(rearWindow, strokePaint)

        strokePaint.color = secondaryColor
        strokePaint.strokeWidth = 1.2f
        canvas.drawLine(82f, 119f, 218f, 119f, strokePaint)
        canvas.drawLine(82f, 237f, 218f, 237f, strokePaint)
    }

    private fun drawWheel(canvas: Canvas, position: String, x: Float, y: Float) {
        fillPaint.color = secondaryColor
        val tire = RectF(x - 17f, y - 23f, x + 17f, y + 23f)
        canvas.drawRoundRect(tire, 5f, 5f, fillPaint)
        strokePaint.color = primaryColor
        strokePaint.strokeWidth = 1.3f
        canvas.drawRoundRect(tire, 5f, 5f, strokePaint)
        strokePaint.color = surfaceColor
        strokePaint.strokeWidth = 2f
        canvas.drawLine(x - 9f, y - 14f, x + 9f, y - 14f, strokePaint)
        canvas.drawLine(x - 9f, y, x + 9f, y, strokePaint)
        canvas.drawLine(x - 9f, y + 14f, x + 9f, y + 14f, strokePaint)

        textPaint.color = primaryColor
        textPaint.textSize = 12f
        val labelY = if (position == "FL" || position == "FR") 48f else 194f
        val idY = if (position == "FL" || position == "FR") 63f else 209f
        canvas.drawText(position, x, labelY, textPaint)
        idPaint.color = accentColor
        idPaint.textSize = 9.5f
        canvas.drawText(ids.getValue(position), x, idY, idPaint)
    }

    private fun drawSpare(canvas: Canvas) {
        val x = 150f
        val y = 316f
        fillPaint.color = secondaryColor
        canvas.drawCircle(x, y, 17f, fillPaint)
        strokePaint.color = primaryColor
        strokePaint.strokeWidth = 1.5f
        canvas.drawCircle(x, y, 17f, strokePaint)
        strokePaint.color = surfaceColor
        strokePaint.strokeWidth = 2f
        canvas.drawCircle(x, y, 9f, strokePaint)
        textPaint.color = primaryColor
        textPaint.textSize = 12f
        canvas.drawText("SP", x, 281f, textPaint)
        idPaint.color = accentColor
        idPaint.textSize = 9.5f
        canvas.drawText(ids.getValue("SP"), x, 296f, idPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val scale = min(width / 300f, height / 360f)
        if (scale <= 0f) return true
        val left = (width - 300f * scale) / 2f
        val top = (height - 360f * scale) / 2f
        val x = (event.x - left) / scale
        val y = (event.y - top) / scale
        val position = touchPoints.entries.minByOrNull { (_, point) ->
            hypot((x - point.first).toDouble(), (y - point.second).toDouble())
        }
        if (position != null) {
            val distance = hypot(
                (x - position.value.first).toDouble(),
                (y - position.value.second).toDouble()
            )
            if (distance <= 36.0) {
                onSensorClick(position.key)
                performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
