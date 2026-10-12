package io.github.ercadion.pipeflow

import java.io.File
import java.util.Locale

/** 목록·홈 카드에 보일 측정 요약 (측정 / 정기 기록 공통) */
data class SessionSummary(
    val dir: File,
    val monitor: Boolean,
    val date: String,        // 2026-10-12 14:32 · v0.3.9
    val title: String,       // 장소 / 관로 ID
    val line: String,        // 수심 42.0 mm · 표면유속 0.620 m/s
    val flags: List<String>, // 저장 전 · 수정됨 · 실측값 · 진행 중 …
    val bytes: Long,
) {
    companion object {
        fun of(dir: File): SessionSummary {
            val n = SessionStore.id(dir)
            val date = (if (n.length >= 13) "${n.substring(0, 4)}-${n.substring(4, 6)}-${n.substring(6, 8)} " +
                "${n.substring(9, 11)}:${n.substring(11, 13)}" else n) + (SessionStore.versionOf(dir)?.let { " · v$it" } ?: "")
            val bytes = SessionStore.sizeOf(dir)
            if (SessionStore.isMonitor(dir)) {
                val m = SessionStore.readJson(File(dir, SessionStore.MONITOR))
                val rows = MonitorLog.read(dir)
                val ok = rows.filter { it["ok"] == "1" }
                val parts = mutableListOf("${m?.optInt("interval_min") ?: "-"}분마다", "기록 ${rows.size}건")
                ok.lastOrNull()?.get("depth_mm")?.toDoubleOrNull()?.let { parts += String.format(Locale.US, "마지막 수심 %.1f mm", it) }
                val st = when (m?.optString("status")) { "running" -> "진행 중"; "finished" -> "끝남"; "stopped" -> "중지함"; else -> "" }
                return SessionSummary(dir, true, date, title(m?.optString("place"), m?.optString("pipe_id")),
                    parts.joinToString(" · "), listOf("정기 기록", st).filter { it.isNotEmpty() }, bytes)
            }
            val auto = SessionStore.autoResult(dir)
            val corr = SessionStore.correctedResult(dir)
            val ann = SessionStore.annotations(dir)
            val r = corr ?: auto
            val parts = mutableListOf<String>()
            if (r == null) parts += "분석 전" else if (!r.optBoolean("ok")) parts += "분석 실패"
            r?.optJSONObject("level")?.let {
                val ad = auto?.optJSONObject("level")?.optDouble("depth_mm")
                parts += if (corr != null && ad != null)
                    String.format(Locale.US, "수심 %.1f mm (자동 %.1f)", it.optDouble("depth_mm"), ad)
                else String.format(Locale.US, "수심 %.1f mm", it.optDouble("depth_mm"))
            }
            r?.optJSONObject("velocity_stiv")?.let { parts += String.format(Locale.US, "유속 %.2f m/s", it.optDouble("v_surface_mps")) }
            val flags = mutableListOf<String>()
            if (SessionStore.isPending(dir)) flags += "저장 전"
            if (corr != null) flags += "수정됨"
            if (listOf("truth_depth_mm", "truth_v_mean_mps", "truth_Q_Lps").any { ann.has(it) }) flags += "실측값"
            return SessionSummary(dir, false, date, title(ann.optString("place"), ann.optString("pipe_id")),
                parts.joinToString(" · "), flags, bytes)
        }

        private fun title(place: String?, pipe: String?): String {
            val t = listOf(place, pipe).filter { !it.isNullOrEmpty() }.joinToString(" / ")
            return t.ifEmpty { "장소 미입력" }
        }
    }
}
