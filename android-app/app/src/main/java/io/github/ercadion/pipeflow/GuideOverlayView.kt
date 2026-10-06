package io.github.ercadion.pipeflow

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 촬영 화면 오버레이
 *  - 실시간으로 포착한 관 내경(빨강) 타원 (outer 를 주면 흰 실선으로 함께 표시)
 *  - 상태: 찾는 중(회색 점선 가이드) / 포착(빨강 내경) / 촬영 중(빨강 굵게 + REC)
 *  - 촬영 준비 체크리스트, 중력 기준 수평선
 */
class GuideOverlayView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    enum class State { SEARCHING, LOCKED, RECORDING }

    /** 체크 항목: 이름, 상태(0 좋음, 1 주의, 2 나쁨), 설명 */
    data class Check(val label: String, val level: Int, val detail: String)

    var state = State.SEARCHING
        set(v) { field = v; invalidate() }
    /** 뷰 좌표 [x0,y0,x1,y1,...] (닫힌 곡선) */
    var outer: FloatArray? = null
        set(v) { field = v; invalidate() }
    var inner: FloatArray? = null
        set(v) { field = v; invalidate() }
    var centerView: FloatArray? = null
    var rollDeg = 0f
        set(v) { field = v; invalidate() }
    var checks: List<Check> = emptyList()
        set(v) { field = v; invalidate() }
    var headline: String = "관 끝단을 화면 가운데에 비추세요"
        set(v) { field = v; invalidate() }
    var ready = false

    private val dp = resources.displayMetrics.density
    private val pOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * dp; color = Color.argb(200, 255, 255, 255) }
    private val pInner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3.5f * dp; color = Color.rgb(255, 45, 45) }
    private val pGuide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * dp; color = Color.argb(150, 200, 200, 200)
        pathEffect = DashPathEffect(floatArrayOf(14 * dp, 10 * dp), 0f)
    }
    private val pLevel = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2 * dp; color = Color.argb(200, 255, 200, 0) }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 14 * dp }
    private val pHead = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 17 * dp; isFakeBoldText = true }
    private val pBox = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val pDot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pRec = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 45, 45) }

    private fun path(p: FloatArray): Path {
        val path = Path()
        path.moveTo(p[0], p[1])
        var i = 2
        while (i + 1 < p.size) { path.lineTo(p[i], p[i + 1]); i += 2 }
        path.close()
        return path
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val o = outer; val inn = inner
        if (state == State.SEARCHING || inn == null) {
            // 찾는 중: 가이드 원(점선)
            val r = min(width, height) * 0.36f
            c.drawCircle(width / 2f, height * 0.42f, r, pGuide)
        } else {
            pInner.strokeWidth = (if (state == State.RECORDING) 5f else 3.5f) * dp
            if (o != null) c.drawPath(path(o), pOuter)
            c.drawPath(path(inn), pInner)
        }
        // 중력 수평선 (관 중심 또는 화면 중심 통과)
        val cv = centerView
        val cx = cv?.get(0) ?: (width / 2f)
        val cy = cv?.get(1) ?: (height * 0.42f)
        val L = min(width, height) * 0.42f
        val a = Math.toRadians(rollDeg.toDouble())
        val dx = (cos(a) * L).toFloat(); val dy = (sin(a) * L).toFloat()
        c.drawLine(cx - dx, cy + dy, cx + dx, cy - dy, pLevel)

        // 상단: 안내문 + 체크리스트
        val pad = 10 * dp
        val lineH = 20 * dp
        val boxH = pad * 2 + 24 * dp + checks.size * lineH
        c.drawRect(0f, 0f, width.toFloat(), boxH, pBox)
        c.drawText(headline, pad, pad + 18 * dp, pHead)
        var y = pad + 24 * dp + 14 * dp
        for (ck in checks) {
            pDot.color = when (ck.level) { 0 -> Color.rgb(60, 220, 90); 1 -> Color.rgb(255, 190, 0); else -> Color.rgb(255, 70, 70) }
            c.drawCircle(pad + 6 * dp, y - 5 * dp, 6 * dp, pDot)
            c.drawText("${ck.label}  ${ck.detail}", pad + 18 * dp, y, pText)
            y += lineH
        }
        if (state == State.RECORDING) {
            c.drawCircle(width - 28 * dp, boxH + 24 * dp, 9 * dp, pRec)
            c.drawText("REC", width - 70 * dp, boxH + 30 * dp, pHead)
        }
    }
}
