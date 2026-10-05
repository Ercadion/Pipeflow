package io.github.ercadion.pipeflow

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 측정 영상 위에 검출 결과(초록 타원, 빨강 수면선)를 그림.
 * editMode 에서는 수면선 양 끝점을 끌어서 수정 가능 (좌표는 영상 px 기준).
 */
class OverlayImageView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) :
    AppCompatImageView(ctx, attrs) {

    data class Ell(val cx: Double, val cy: Double, val a: Double, val b: Double, val phiDeg: Double)

    var ellipse: Ell? = null
        set(v) { field = v; invalidate() }
    /** [x1, y1, x2, y2] 영상 좌표 */
    var waterline: DoubleArray? = null
        set(v) { field = v; invalidate() }
    var editMode = false
        set(v) { field = v; invalidate() }
    var onEdited: (() -> Unit)? = null

    private val pEll = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f; color = Color.rgb(0, 230, 0) }
    private val pWl = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f; color = Color.rgb(255, 50, 50) }
    private val pHandle = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.argb(160, 255, 50, 50) }
    private var dragIdx = -1

    private fun toView(x: Double, y: Double): FloatArray {
        val pts = floatArrayOf(x.toFloat(), y.toFloat())
        imageMatrix.mapPoints(pts)
        pts[0] += paddingLeft; pts[1] += paddingTop
        return pts
    }

    private fun toImage(x: Float, y: Float): FloatArray {
        val inv = Matrix(); imageMatrix.invert(inv)
        val pts = floatArrayOf(x - paddingLeft, y - paddingTop)
        inv.mapPoints(pts)
        return pts
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        ellipse?.let { e ->
            val path = Path()
            val c = cos(Math.toRadians(e.phiDeg)); val s = sin(Math.toRadians(e.phiDeg))
            for (i in 0..120) {
                val t = 2 * Math.PI * i / 120
                val x = e.a * cos(t); val y = e.b * sin(t)
                val p = toView(e.cx + c * x - s * y, e.cy + s * x + c * y)
                if (i == 0) path.moveTo(p[0], p[1]) else path.lineTo(p[0], p[1])
            }
            canvas.drawPath(path, pEll)
        }
        waterline?.let { w ->
            val a = toView(w[0], w[1]); val b = toView(w[2], w[3])
            canvas.drawLine(a[0], a[1], b[0], b[1], pWl)
            if (editMode) {
                canvas.drawCircle(a[0], a[1], 36f, pHandle)
                canvas.drawCircle(b[0], b[1], 36f, pHandle)
            }
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!editMode) return super.onTouchEvent(ev)
        val w = waterline ?: return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val a = toView(w[0], w[1]); val b = toView(w[2], w[3])
                val da = hypot((ev.x - a[0]).toDouble(), (ev.y - a[1]).toDouble())
                val db = hypot((ev.x - b[0]).toDouble(), (ev.y - b[1]).toDouble())
                dragIdx = if (minOf(da, db) < 120) (if (da < db) 0 else 1) else -1
                parent.requestDisallowInterceptTouchEvent(dragIdx >= 0)
            }
            MotionEvent.ACTION_MOVE -> if (dragIdx >= 0) {
                val p = toImage(ev.x, ev.y)
                w[dragIdx * 2] = p[0].toDouble(); w[dragIdx * 2 + 1] = p[1].toDouble()
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragIdx >= 0) onEdited?.invoke()
                dragIdx = -1
                parent.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
}
