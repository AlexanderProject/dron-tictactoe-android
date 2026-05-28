package com.example.djimichi.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.example.djimichi.game.Cell

class TicTacToeOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onCellTapped: ((Int) -> Unit)? = null
    var inputEnabled: Boolean = false

    var cells: Array<Cell> = Array(9) { Cell.EMPTY }
        set(value) { field = value; invalidate() }

    /** Casilla donde está parado el dron ahora mismo (borde verde). */
    var currentCell: Int? = null
        set(value) { field = value; invalidate() }

    /** Casilla destino indicada por la IA (borde amarillo pulsante). */
    var targetCell: Int? = null
        set(value) {
            field = value
            if (value != null) pulseAnimator.start()
            else { pulseAnimator.cancel(); targetCellPaint.alpha = 255 }
            invalidate()
        }

    // --- Paints ---

    private val linePaint = Paint().apply {
        color = Color.WHITE; strokeWidth = 8f; style = Paint.Style.STROKE
    }
    private val humanPaint = Paint().apply {
        color = Color.BLUE; strokeWidth = 12f; style = Paint.Style.STROKE
    }
    private val dronePaint = Paint().apply {
        color = Color.RED; strokeWidth = 12f; style = Paint.Style.STROKE
    }
    private val currentCellPaint = Paint().apply {
        color = Color.GREEN; strokeWidth = 10f; style = Paint.Style.STROKE
    }
    private val targetCellPaint = Paint().apply {
        color = Color.YELLOW; strokeWidth = 10f; style = Paint.Style.STROKE
    }

    // Pulsa el alpha de targetCellPaint entre 255 y 80 (≈30 %) cada 800 ms
    private val pulseAnimator = ValueAnimator.ofInt(255, 80).apply {
        duration = 800
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { anim ->
            targetCellPaint.alpha = anim.animatedValue as Int
            if (targetCell != null) invalidate()
        }
    }

    // --- Dibujo ---

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cellW = w / 3
        val cellH = h / 3

        // Grilla
        canvas.drawLine(w / 3, 0f, w / 3, h, linePaint)
        canvas.drawLine(2 * w / 3, 0f, 2 * w / 3, h, linePaint)
        canvas.drawLine(0f, h / 3, w, h / 3, linePaint)
        canvas.drawLine(0f, 2 * h / 3, w, 2 * h / 3, linePaint)

        for (i in 0..8) {
            val row = 2 - (i / 3)   // invierte filas para acoplar con perspectiva del dron
            val col = i % 3
            val left = col * cellW
            val top  = row * cellH

            // Borde verde: posición actual del dron
            if (currentCell == i) {
                val inset = 6f
                canvas.drawRect(left + inset, top + inset, left + cellW - inset, top + cellH - inset, currentCellPaint)
            }

            // Borde amarillo pulsante: celda destino de la IA
            if (targetCell == i) {
                val inset = 6f
                canvas.drawRect(left + inset, top + inset, left + cellW - inset, top + cellH - inset, targetCellPaint)
            }

            when (cells[i]) {
                Cell.HUMAN -> canvas.drawCircle(
                    left + cellW / 2, top + cellH / 2, cellW / 3, humanPaint
                )
                Cell.DRONE -> {
                    val offset = cellW / 4
                    canvas.drawLine(left + offset, top + offset, left + cellW - offset, top + cellH - offset, dronePaint)
                    canvas.drawLine(left + cellW - offset, top + offset, left + offset, top + cellH - offset, dronePaint)
                }
                else -> {}
            }
        }
    }

    // --- Touch (mantenido para debugging; en producción no se wirea onCellTapped) ---

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!inputEnabled || event.action != MotionEvent.ACTION_DOWN) return false
        val cellW = width / 3
        val cellH = height / 3
        val col    = (event.x / cellW).toInt().coerceIn(0, 2)
        val viewRow = (event.y / cellH).toInt().coerceIn(0, 2)
        val row    = 2 - viewRow
        onCellTapped?.invoke(row * 3 + col)
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator.cancel()
    }
}
