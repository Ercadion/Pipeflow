package io.github.ercadion.pipeflow

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** 촬영 가이드: 관 단면을 맞출 원 + 중력 기준 수평선(폰 기울기 표시) */
class GuideOverlayView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var rollDeg: Float = 0f
        set(v) { field = v; invalidate() }
    var recording = false
        set(v) { field = v; invalidate() }

    private val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5f; color = Color.argb(200, 0, 230, 120)
    }
    private val level = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 4f; color = Color.argb(200, 255, 200, 0) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 40f }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val cx = width / 2f; val cy = height * 0.42f
        val r = min(width, height) * 0.36f
        circle.color = if (recording) Color.argb(220, 255, 60, 60) else Color.argb(200, 0, 230, 120)
        c.drawCircle(cx, cy, r, circle)
        c.drawLine(cx - 20, cy, cx + 20, cy, circle)
        c.drawLine(cx, cy - 20, cx, cy + 20, circle)
        // 중력 기준 수평선 (수면은 이 선과 평행해야 함)
        val a = Math.toRadians(rollDeg.toDouble())
        val dx = (cos(a) * r * 1.15).toFloat(); val dy = (sin(a) * r * 1.15).toFloat()
        c.drawLine(cx - dx, cy + dy, cx + dx, cy - dy, level)
        c.drawText(if (recording) "촬영 중 — 움직이지 마세요" else "관 끝단을 원에 맞추세요 (화면의 60~80%)",
            40f, 80f, text)
    }
}
