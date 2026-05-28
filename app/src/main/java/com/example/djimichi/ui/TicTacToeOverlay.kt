package com.example.djimichi.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.example.djimichi.game.Cell
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Overlay del tablero de Tic-Tac-Toe superpuesto al video del dron.
 *
 * Responsabilidades visuales:
 *  • Grilla 3×3 blanca
 *  • Piezas: O azul (humano), X roja (dron)
 *  • Borde verde: celda donde está parado el dron ahora mismo
 *  • Borde amarillo pulsante + flecha: celda destino de la IA
 *  • Arco de progreso verde: feedback del dwell GPS (0 → lleno = confirmado)
 *
 * Convención de índices (igual que BoardCalibrator):
 *   6 | 7 | 8    ← adelante
 *   3 | 4 | 5
 *   0 | 1 | 2    ← atrás
 * En pantalla la fila superior corresponde al índice 6-8 (row invertido).
 */
class TicTacToeOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── API pública ────────────────────────────────────────────────────────────

    var onCellTapped: ((Int) -> Unit)? = null
    var inputEnabled: Boolean = false

    var cells: Array<Cell> = Array(9) { Cell.EMPTY }
        set(value) { field = value; invalidate() }

    // ── Estado interno ─────────────────────────────────────────────────────────

    private var currentDroneCell: Int? = null   // borde verde
    private var droneTargetCell:  Int? = null   // borde amarillo + flecha
    private var dwellSweep:       Float = 0f    // ángulo del arco de progreso (0-360)

    // ── Paints ─────────────────────────────────────────────────────────────────

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
    private val arrowPaint = Paint().apply {
        color = Color.YELLOW; strokeWidth = 7f
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val arcPaint = Paint().apply {
        color = Color.GREEN; strokeWidth = 14f
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }

    // Pulsa el alpha de targetCellPaint y arrowPaint entre 255 y 70
    private val pulseAnimator = ValueAnimator.ofInt(255, 70).apply {
        duration = 700
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { anim ->
            val alpha = anim.animatedValue as Int
            targetCellPaint.alpha = alpha
            arrowPaint.alpha      = alpha
            if (droneTargetCell != null) invalidate()
        }
    }

    // ── Métodos públicos de control ────────────────────────────────────────────

    /** Borde verde sobre la celda donde está el dron ahora. null = sin highlight. */
    fun setCurrentDroneCell(index: Int?) {
        currentDroneCell = index
        invalidate()
    }

    /**
     * Activa el highlight amarillo pulsante + flecha hacia la celda [index].
     * Resetea el arco de progreso.
     */
    fun setDroneTarget(index: Int) {
        droneTargetCell = index
        dwellSweep      = 0f
        targetCellPaint.alpha = 255
        arrowPaint.alpha      = 255
        pulseAnimator.start()
        invalidate()
    }

    /**
     * Actualiza el arco de progreso del dwell.
     * [current] / [total] determina la fracción del arco (0 = vacío, total = lleno).
     */
    fun setDwellProgress(current: Int, total: Int) {
        dwellSweep = if (total > 0) (current.toFloat() / total * 360f).coerceIn(0f, 360f) else 0f
        invalidate()
    }

    /** Limpia flecha, highlight amarillo y arco de progreso. */
    fun clearDroneTarget() {
        droneTargetCell       = null
        dwellSweep            = 0f
        targetCellPaint.alpha = 255
        arrowPaint.alpha      = 255
        pulseAnimator.cancel()
        invalidate()
    }

    // ── Dibujo ─────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w     = width.toFloat()
        val h     = height.toFloat()
        val cellW = w / 3f
        val cellH = h / 3f

        // 1. Grilla
        canvas.drawLine(w / 3, 0f, w / 3, h, linePaint)
        canvas.drawLine(2 * w / 3, 0f, 2 * w / 3, h, linePaint)
        canvas.drawLine(0f, h / 3, w, h / 3, linePaint)
        canvas.drawLine(0f, 2 * h / 3, w, 2 * h / 3, linePaint)

        for (i in 0..8) {
            val viewRow = 2 - (i / 3)   // invierte fila para perspectiva del dron
            val col     = i % 3
            val left    = col * cellW
            val top     = viewRow * cellH

            // 2. Borde verde: posición actual del dron
            if (currentDroneCell == i) {
                val inset = 6f
                canvas.drawRect(left + inset, top + inset,
                    left + cellW - inset, top + cellH - inset, currentCellPaint)
            }

            // 3. Borde amarillo pulsante: celda destino de la IA
            if (droneTargetCell == i) {
                val inset = 6f
                canvas.drawRect(left + inset, top + inset,
                    left + cellW - inset, top + cellH - inset, targetCellPaint)
            }

            // 4. Piezas
            val cx = left + cellW / 2
            val cy = top  + cellH / 2
            when (cells[i]) {
                Cell.HUMAN -> canvas.drawCircle(cx, cy, cellW / 3f, humanPaint)
                Cell.DRONE -> {
                    val off = cellW / 4f
                    canvas.drawLine(left + off, top + off, left + cellW - off, top + cellH - off, dronePaint)
                    canvas.drawLine(left + cellW - off, top + off, left + off, top + cellH - off, dronePaint)
                }
                else -> {}
            }
        }

        // 5. Flecha desde el centro del tablero a la celda destino
        droneTargetCell?.let { target ->
            val tRow = 2 - (target / 3)
            val tCol = target % 3
            val tx   = tCol * cellW + cellW / 2f
            val ty   = tRow * cellH + cellH / 2f
            val bx   = w / 2f
            val by   = h / 2f

            // Solo dibuja la flecha si el dron NO está ya en la celda destino
            if (currentDroneCell != target) {
                val angle     = atan2((ty - by).toDouble(), (tx - bx).toDouble())
                val arrowHead = min(cellW, cellH) / 4f
                val wingAngle = Math.toRadians(30.0)

                canvas.drawLine(bx, by, tx, ty, arrowPaint)
                canvas.drawLine(
                    tx, ty,
                    (tx - arrowHead * cos(angle - wingAngle)).toFloat(),
                    (ty - arrowHead * sin(angle - wingAngle)).toFloat(),
                    arrowPaint
                )
                canvas.drawLine(
                    tx, ty,
                    (tx - arrowHead * cos(angle + wingAngle)).toFloat(),
                    (ty - arrowHead * sin(angle + wingAngle)).toFloat(),
                    arrowPaint
                )
            }

            // 6. Arco de progreso (dwell) alrededor de la celda destino
            if (dwellSweep > 0f) {
                val arcR  = min(cellW, cellH) / 3f
                val arcCx = tCol * cellW + cellW / 2f
                val arcCy = tRow * cellH + cellH / 2f
                val rect  = RectF(arcCx - arcR, arcCy - arcR, arcCx + arcR, arcCy + arcR)
                canvas.drawArc(rect, -90f, dwellSweep, false, arcPaint)
            }
        }
    }

    // ── Touch ──────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!inputEnabled || event.action != MotionEvent.ACTION_DOWN) return false
        val cellW   = width / 3
        val cellH   = height / 3
        val col     = (event.x / cellW).toInt().coerceIn(0, 2)
        val viewRow = (event.y / cellH).toInt().coerceIn(0, 2)
        val row     = 2 - viewRow
        onCellTapped?.invoke(row * 3 + col)
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator.cancel()
    }
}
