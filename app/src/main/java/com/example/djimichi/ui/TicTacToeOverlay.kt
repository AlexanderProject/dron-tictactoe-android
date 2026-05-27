package com.example.djimichi.ui

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
    var enabled: Boolean = false
    var cells: Array<Cell> = Array(9) { Cell.EMPTY }
        set(value) {
            field = value
            invalidate() // Redibuja la grilla al cambiar el estado
        }

    private val linePaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 8f
        style = Paint.Style.STROKE
    }

    private val humanPaint = Paint().apply {
        color = Color.BLUE
        strokeWidth = 12f
        style = Paint.Style.STROKE
    }

    private val dronePaint = Paint().apply {
        color = Color.RED
        strokeWidth = 12f
        style = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // Dibujar líneas del Michi (Grilla 3x3)
        canvas.drawLine(w / 3, 0f, w / 3, h, linePaint)
        canvas.drawLine(2 * w / 3, 0f, 2 * w / 3, h, linePaint)
        canvas.drawLine(0f, h / 3, w, h / 3, linePaint)
        canvas.drawLine(0f, 2 * h / 3, w, 2 * h / 3, linePaint)

        // Dibujar las jugadas (X y O)
        val cellW = w / 3
        val cellH = h / 3

        for (i in 0..8) {
            val row = 2 - (i / 3) // Invierte filas para acoplar la vista espejo del dron
            val col = i % 3
            val left = col * cellW
            val top = row * cellH

            when (cells[i]) {
                Cell.HUMAN -> {
                    // Dibuja un Círculo Azul para el Humano
                    canvas.drawCircle(left + cellW / 2, top + cellH / 2, cellW / 3, humanPaint)
                }
                Cell.DRONE -> {
                    // Dibuja una Equis Roja para el Dron
                    val offset = cellW / 4
                    canvas.drawLine(left + offset, top + offset, left + cellW - offset, top + cellH - offset, dronePaint)
                    canvas.drawLine(left + cellW - offset, top + offset, left + offset, top + cellH - offset, dronePaint)
                }
                else -> {}
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!enabled || event.action != MotionEvent.ACTION_DOWN) return false

        val cellW = width / 3
        val cellH = height / 3
        val col = (event.x / cellW).toInt().coerceIn(0, 2)
        val viewRow = (event.y / cellH).toInt().coerceIn(0, 2)
        val row = 2 - viewRow // Re-mapea la coordenada física de la pantalla al índice del backend

        val index = row * 3 + col
        onCellTapped?.invoke(index)
        return true
    }
}