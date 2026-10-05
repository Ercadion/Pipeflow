package io.github.ercadion.pipeflow

import android.content.Context
import org.json.JSONObject

/** 측정 설정 (SharedPreferences 저장). meta.json 의 params 로도 기록되어 세션이 자급자족. */
data class Settings(
    var diameterMm: Double = 100.0,
    var wallMm: Double = 0.0,
    var slopePercent: Double? = null,
    var material: String = "glass",
    var textureSign: Int = 1,
    var fps: Int = 60,
    var durationSec: Double = 3.0,
) {
    fun toParams(): JSONObject = JSONObject().apply {
        put("diameter_mm", diameterMm)
        put("wall_mm", wallMm)
        slopePercent?.let { put("slope", it / 100.0) }
        put("material", material)
        put("texture_sign", textureSign)
        put("fps_target", fps)
        put("duration_s", durationSec)
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("json", toParams().toString()).apply()
    }

    companion object {
        val MATERIALS = listOf(
            "glass" to "유리 (n=0.010)", "acrylic" to "아크릴 (n=0.009)", "pvc" to "PVC (n=0.009)",
            "hdpe" to "HDPE (n=0.011)", "steel" to "강관 (n=0.012)", "cast_iron" to "주철 (n=0.013)",
            "concrete" to "콘크리트 (n=0.013)", "corrugated" to "주름관 (n=0.024)"
        )
        val TEXTURES = listOf(
            1 to "물 쪽이 더 거침 (랩 주름·굴절, 기본)", 0 to "모름 / 상관없음", -1 to "물 쪽이 더 매끈"
        )
        val FPS = listOf(60, 30)
        val DURATIONS = listOf(3.0, 5.0, 8.0)

        fun load(ctx: Context): Settings {
            val s = Settings()
            val js = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("json", null)
                ?: return s
            val o = JSONObject(js)
            s.diameterMm = o.optDouble("diameter_mm", 100.0)
            s.wallMm = o.optDouble("wall_mm", 0.0)
            s.slopePercent = if (o.has("slope")) o.getDouble("slope") * 100.0 else null
            s.material = o.optString("material", "glass")
            s.textureSign = o.optInt("texture_sign", 1)
            s.fps = o.optInt("fps_target", 60)
            s.durationSec = o.optDouble("duration_s", 3.0)
            return s
        }
    }
}
