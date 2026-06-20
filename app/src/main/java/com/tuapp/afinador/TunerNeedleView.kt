package com.tuapp.afinador

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Dibuja un medidor tipo "aguja" que se mueve según los cents de desviación
 * (-50 a +50). En el centro está afinado.
 */
class TunerNeedleView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var cents: Float = 0f
    private var inTune: Boolean = false

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#46392A")
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }
    private val centerTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D98A4F")
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E9DDC8")
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
    }
    private val needleInTunePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6FB583")
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
    }
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D98A4F")
    }

    /** Establece la desviación actual en cents (-50..50) y redibuja. */
    fun setCents(value: Float?) {
        if (value == null) {
            cents = 0f
            inTune = false
        } else {
            cents = value.coerceIn(-50f, 50f)
            inTune = kotlin.math.abs(value) <= 4
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h - 10f
        val needleLen = h - 30f

        // Ticks de -50 a 50 en pasos de 10
        var deg = -50
        while (deg <= 50) {
            val isCenter = deg == 0
            val rad = Math.toRadians((deg - 90).toDouble())
            val r1 = if (isCenter) (h * 0.45f) else (h * 0.35f)
            val r2 = h * 0.6f
            val x1 = cx + r1 * cos(rad).toFloat()
            val y1 = cy + r1 * sin(rad).toFloat()
            val x2 = cx + r2 * cos(rad).toFloat()
            val y2 = cy + r2 * sin(rad).toFloat()
            canvas.drawLine(x1, y1, x2, y2, if (isCenter) centerTickPaint else tickPaint)
            deg += 10
        }

        // Aguja
        val angleRad = Math.toRadians((cents - 90).toDouble())
        val tipX = cx + needleLen * cos(angleRad).toFloat()
        val tipY = cy + needleLen * sin(angleRad).toFloat()
        canvas.drawLine(cx, cy, tipX, tipY, if (inTune) needleInTunePaint else needlePaint)

        // Base de la aguja
        canvas.drawCircle(cx, cy, 12f, basePaint)
    }
}
