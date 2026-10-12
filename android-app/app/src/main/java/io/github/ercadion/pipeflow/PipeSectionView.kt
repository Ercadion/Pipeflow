package io.github.ercadion.pipeflow

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import java.util.Locale
import kotlin.math.min

/** 관 단면 그림: 원(관 내경) 안에 수심만큼 물을 채우고 내경·수심 치수 표시 */
class PipeSectionView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var diameterMm = Double.NaN
        set(v) { field = v; invalidate() }
    var depthMm = Double.NaN
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    private val brand = Color.rgb(0x2F, 0x6F, 0xED)
    private val pPipe = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5 * d; color = Color.rgb(0x9A, 0xA8, 0xBD) }
    private val pInner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.rgb(0xF4, 0xF7, 0xFC) }
    private val pWater = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.rgb(0x4F, 0x8D, 0xF7) }
    private val pSurface = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d; color = brand }
    private val pDim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * d; color = Color.rgb(0x6B, 0x75, 0x88) }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x1A, 0x22, 0x33); textSize = 12 * sp; textAlign = Paint.Align.CENTER }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val top = 26 * d
        val r = min(width - 120 * d, height - top - 10 * d) / 2f
        if (r <= 0) return
        val cx = width / 2f; val cy = top + r + 4 * d
        c.drawCircle(cx, cy, r, pInner)
        val frac = if (diameterMm > 0 && !depthMm.isNaN()) (depthMm / diameterMm).coerceIn(0.0, 1.0).toFloat() else 0f
        val ys = cy + r - 2 * r * frac
        if (frac > 0f) {
            c.save()
            val clip = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
            c.clipPath(clip)
            c.drawRect(cx - r, ys, cx + r, cy + r, pWater)
            c.drawLine(cx - r, ys, cx + r, ys, pSurface)
            c.restore()
        }
        c.drawCircle(cx, cy, r, pPipe)
        // 내경 치수 (위)
        if (diameterMm > 0) {
            val y = top - 10 * d
            c.drawLine(cx - r, y, cx + r, y, pDim)
            c.drawLine(cx - r, y - 4 * d, cx - r, y + 4 * d, pDim)
            c.drawLine(cx + r, y - 4 * d, cx + r, y + 4 * d, pDim)
            c.drawText(String.format(Locale.US, "내경 %.0f mm", diameterMm), cx, y - 5 * d, pText)
        }
        // 수심 치수 (오른쪽)
        if (frac > 0f) {
            val x = cx + r + 18 * d
            c.drawLine(x, ys, x, cy + r, pDim)
            c.drawLine(x - 4 * d, ys, x + 4 * d, ys, pDim)
            c.drawLine(x - 4 * d, cy + r, x + 4 * d, cy + r, pDim)
            pText.textAlign = Paint.Align.LEFT
            c.drawText("수심", x + 6 * d, (ys + cy + r) / 2 - 2 * d, pText)
            c.drawText(String.format(Locale.US, "%.1f mm", depthMm), x + 6 * d, (ys + cy + r) / 2 + 13 * d, pText)
            pText.textAlign = Paint.Align.CENTER
        }
    }
}
