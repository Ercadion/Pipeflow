package io.github.ercadion.pipeflow

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * 정기 기록의 수심 그래프 (시간 → 수심 mm)
 *  - 실패한 기록(수심 없음)은 선을 끊어 빈 구간으로 보임
 *  - x 범위: tStart~tEnd (주어지지 않으면 기록의 처음~끝)
 */
class DepthChartView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    /** 시각(ms) · 수심(mm, 실패면 NaN) */
    var points: List<Pair<Long, Double>> = emptyList()
        set(v) { field = v; invalidate() }
    var tStart: Long? = null
    var tEnd: Long? = null
    var dark = false
        set(v) { field = v; invalidate() }

    private val dens = resources.displayMetrics.density
    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * dens; color = Color.rgb(33, 118, 210) }
    private val pDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.rgb(33, 118, 210) }
    private val pGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * resources.displayMetrics.scaledDensity }
    private val pFail = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.rgb(220, 60, 60) }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        pGrid.color = if (dark) Color.argb(80, 255, 255, 255) else Color.argb(60, 0, 0, 0)
        pText.color = if (dark) Color.rgb(200, 200, 200) else Color.rgb(90, 90, 90)
        val ok = points.filter { !it.second.isNaN() }
        val left = 44 * dens; val right = width - 12 * dens; val top = 12 * dens; val bottom = height - 26 * dens
        if (right <= left || bottom <= top) return
        if (points.isEmpty()) {
            c.drawText("아직 기록이 없습니다", left, (top + bottom) / 2, pText); return
        }
        val t0 = tStart ?: points.first().first
        val t1 = max(tEnd ?: points.last().first, t0 + 60_000L)
        var lo = ok.minOfOrNull { it.second } ?: 0.0
        var hi = ok.maxOfOrNull { it.second } ?: 1.0
        if (hi - lo < 2.0) { val m = (hi + lo) / 2; lo = m - 1; hi = m + 1 }
        val pad = (hi - lo) * 0.1; lo -= pad; hi += pad
        fun X(t: Long) = left + (right - left) * ((t - t0).toDouble() / (t1 - t0)).toFloat()
        fun Y(v: Double) = bottom - (bottom - top) * ((v - lo) / (hi - lo)).toFloat()
        for (i in 0..4) {
            val v = lo + (hi - lo) * i / 4
            val y = Y(v)
            c.drawLine(left, y, right, y, pGrid)
            c.drawText(String.format(Locale.US, "%.0f", v), 4 * dens, y + 4 * dens, pText)
        }
        c.drawText("mm", 4 * dens, top + 2 * dens, pText)
        val fmt = SimpleDateFormat(if (t1 - t0 > 86_400_000L) "MM-dd HH:mm" else "HH:mm", Locale.US)
        c.drawText(fmt.format(Date(t0)), left, height - 6 * dens, pText)
        val endLabel = fmt.format(Date(t1))
        c.drawText(endLabel, right - pText.measureText(endLabel), height - 6 * dens, pText)
        val path = Path(); var pen = false
        for ((t, v) in points) {
            if (v.isNaN()) { pen = false; c.drawCircle(X(t), bottom - 3 * dens, 2.5f * dens, pFail); continue }
            if (pen) path.lineTo(X(t), Y(v)) else path.moveTo(X(t), Y(v))
            pen = true
        }
        c.drawPath(path, pLine)
        if (ok.size <= 60) ok.forEach { c.drawCircle(X(it.first), Y(it.second), 2.5f * dens, pDot) }
        else ok.lastOrNull()?.let { c.drawCircle(X(it.first), Y(it.second), 3.5f * dens, pDot) }
    }
}
