package io.github.ercadion.pipeflow

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 측정 영상 위에 검출 결과(초록 타원 = 관 내경, 빨강 수면선)를 그림.
 *  - autoWaterline / autoEllipse: 사람이 고친 결과와 비교할 때 자동 검출 수면선·테두리를 주황 점선으로 함께 표시
 *  - editMode 에서는 수면선 양 끝점을 끌어서 수정 (좌표는 영상 px 기준)
 *  - 보기 모드에서 초록 타원·빨강 선·주황 점선을 누르면 onTapTarget 이 준 수치를 작은 상자로 3초간 표시
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
    /** 자동 검출 수면선 (수정했을 때만, 주황 점선) */
    var autoWaterline: DoubleArray? = null
        set(v) { field = v; invalidate() }
    /** 자동 검출 테두리 (고친 결과와 비교할 때, 주황 점선 타원) */
    var autoEllipse: Ell? = null
        set(v) { field = v; invalidate() }
    /** 누른 대상("ellipse" | "waterline" | "auto_waterline" | "auto_ellipse") → 상자에 보일 글 (null 이면 표시 안 함) */
    var onTapTarget: ((String) -> String?)? = null

    private val dens = resources.displayMetrics.density
    private val pAuto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5f; color = Color.rgb(255, 170, 0)
        pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
    }
    private val pTipBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(215, 20, 20, 20) }
    private val pTipBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    private val pTipText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 13 * resources.displayMetrics.scaledDensity }
    private var tipText: String? = null
    private var tipX = 0f
    private var tipY = 0f
    private var tipColor = Color.WHITE
    private val hideTip = Runnable { tipText = null; invalidate() }
    private var downX = 0f
    private var downY = 0f
    private var downTarget: String? = null

    private val pEll = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f; color = Color.rgb(0, 230, 0) }
    private val pWl = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f; color = Color.rgb(255, 50, 50) }
    private val pHandle = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.argb(160, 255, 50, 50) }
    private var dragIdx = -1

    private fun toView(x: Double, y: Double): FloatArray {
        val pts = floatArrayOf(x.toFloat(), y.toFloat())
        imageMatrix.mapPoints(pts)
        return floatArrayOf(pts[0] + paddingLeft.toFloat(), pts[1] + paddingTop.toFloat())
    }

    private fun toImage(x: Float, y: Float): FloatArray {
        val inv = Matrix(); imageMatrix.invert(inv)
        val pts = floatArrayOf(x - paddingLeft.toFloat(), y - paddingTop.toFloat())
        inv.mapPoints(pts)
        return pts
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        autoEllipse?.let { drawEll(canvas, it, pAuto) }
        ellipse?.let { drawEll(canvas, it, pEll) }
        autoWaterline?.let { w ->
            val a = toView(w[0], w[1]); val b = toView(w[2], w[3])
            canvas.drawLine(a[0], a[1], b[0], b[1], pAuto)
        }
        waterline?.let { w ->
            val a = toView(w[0], w[1]); val b = toView(w[2], w[3])
            canvas.drawLine(a[0], a[1], b[0], b[1], pWl)
            if (editMode) {
                canvas.drawCircle(a[0], a[1], 36f, pHandle)
                canvas.drawCircle(b[0], b[1], 36f, pHandle)
            }
        }
        drawTip(canvas)
    }

    private fun drawEll(canvas: Canvas, e: Ell, paint: Paint) {
        val path = Path()
        val c = cos(Math.toRadians(e.phiDeg)); val s = sin(Math.toRadians(e.phiDeg))
        for (i in 0..120) {
            val t = 2 * Math.PI * i / 120
            val x = e.a * cos(t); val y = e.b * sin(t)
            val p = toView(e.cx + c * x - s * y, e.cy + s * x + c * y)
            if (i == 0) path.moveTo(p[0], p[1]) else path.lineTo(p[0], p[1])
        }
        canvas.drawPath(path, paint)
    }

    // ---------------------------------------------------------------- 누른 대상 찾기·수치 상자
    private fun segDist(px: Float, py: Float, w: DoubleArray): Double {
        val a = toView(w[0], w[1]); val b = toView(w[2], w[3])
        val dx = (b[0] - a[0]).toDouble(); val dy = (b[1] - a[1]).toDouble()
        val l2 = dx * dx + dy * dy
        val t = if (l2 < 1e-9) 0.0 else (((px - a[0]) * dx + (py - a[1]) * dy) / l2).coerceIn(0.0, 1.0)
        return hypot(px - (a[0] + t * dx), py - (a[1] + t * dy))
    }

    private fun ellDist(px: Float, py: Float, e: Ell): Double {
        val c = cos(Math.toRadians(e.phiDeg)); val s = sin(Math.toRadians(e.phiDeg))
        var best = Double.MAX_VALUE
        for (i in 0 until 180) {
            val t = 2 * Math.PI * i / 180
            val x = e.a * cos(t); val y = e.b * sin(t)
            val p = toView(e.cx + c * x - s * y, e.cy + s * x + c * y)
            best = minOf(best, hypot((px - p[0]).toDouble(), (py - p[1]).toDouble()))
        }
        return best
    }

    /** 가장 가까운 대상 (손가락 두께 고려 24dp 이내) */
    private fun hitTarget(x: Float, y: Float): String? {
        val tol = 24 * dens
        val c = ArrayList<Pair<String, Double>>()
        waterline?.let { c += "waterline" to segDist(x, y, it) }
        autoWaterline?.let { c += "auto_waterline" to segDist(x, y, it) }
        ellipse?.let { c += "ellipse" to ellDist(x, y, it) }
        autoEllipse?.let { c += "auto_ellipse" to ellDist(x, y, it) }
        return c.filter { it.second < tol }.minByOrNull { it.second }?.first
    }

    private fun showTip(kind: String, x: Float, y: Float) {
        val text = onTapTarget?.invoke(kind) ?: return
        tipText = text; tipX = x; tipY = y
        tipColor = when (kind) { "ellipse" -> Color.rgb(0, 230, 0); "auto_waterline", "auto_ellipse" -> Color.rgb(255, 170, 0); else -> Color.rgb(255, 50, 50) }
        removeCallbacks(hideTip)
        postDelayed(hideTip, 3000)
        invalidate()
    }

    private fun drawTip(canvas: Canvas) {
        val t = tipText ?: return
        val lines = t.split('\n')
        val pad = 8 * dens
        val lh = pTipText.textSize * 1.3f
        val w = (lines.maxOfOrNull { pTipText.measureText(it) } ?: 0f) + 2 * pad
        val h = lines.size * lh + 2 * pad - (lh - pTipText.textSize)
        var left = tipX - w / 2
        var top = tipY - h - 18 * dens
        if (top < 0) top = tipY + 18 * dens
        left = left.coerceIn(4f, maxOf(4f, width - w - 4f))
        top = top.coerceIn(4f, maxOf(4f, height - h - 4f))
        val r = RectF(left, top, left + w, top + h)
        canvas.drawRoundRect(r, 10 * dens, 10 * dens, pTipBg)
        pTipBorder.color = tipColor
        canvas.drawRoundRect(r, 10 * dens, 10 * dens, pTipBorder)
        lines.forEachIndexed { i, s -> canvas.drawText(s, left + pad, top + pad + pTipText.textSize + i * lh, pTipText) }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(hideTip)
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!editMode) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x; downY = ev.y
                    downTarget = hitTarget(ev.x, ev.y)
                    if (downTarget == null) return super.onTouchEvent(ev)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val k = downTarget
                    downTarget = null
                    if (k != null && hypot((ev.x - downX).toDouble(), (ev.y - downY).toDouble()) < 20 * dens) {
                        showTip(k, ev.x, ev.y); performClick()
                    }
                    return k != null || super.onTouchEvent(ev)
                }
                MotionEvent.ACTION_CANCEL -> { downTarget = null }
            }
            return downTarget != null || super.onTouchEvent(ev)
        }
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
