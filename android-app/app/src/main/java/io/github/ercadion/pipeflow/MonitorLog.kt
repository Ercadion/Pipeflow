package io.github.ercadion.pipeflow

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 정기 기록 표 records.csv — 한 줄 = 기록 시각 1번 (실패·놓친 시각도 한 줄, 수심은 빈칸)
 * 덧붙이기만 함 (기록 중 앱이 꺼져도 그때까지는 남음)
 * 수면선 wl_* = 회전 보정한 원 영상 좌표 (사진 img/ 폴더의 jpg 는 monitor.json 의 thumb_max_side 로 축소된 것)
 */
object MonitorLog {
    val HEADER = listOf("time", "epoch_ms", "ok", "depth_mm", "fill_pct", "confidence", "roll_deg",
        "brightness", "torch", "battery_pct", "charging", "image", "note", "wl_x1", "wl_y1", "wl_x2", "wl_y2")

    private val tf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun ensureHeader(dir: File) {
        val f = File(dir, SessionStore.RECORDS)
        if (!f.exists() || f.length() == 0L) f.writeText(HEADER.joinToString(",") + "\n")
    }

    private fun cell(v: Any?): String = when (v) {
        null -> ""
        is Double -> if (v.isNaN()) "" else String.format(Locale.US, "%.3f", v)
        is Boolean -> if (v) "1" else "0"
        else -> v.toString().replace(',', ';').replace('\n', ' ')
    }

    /** values 의 키 = HEADER (time 은 epoch_ms 로 자동) */
    fun append(dir: File, epochMs: Long, values: Map<String, Any?>) {
        ensureHeader(dir)
        val row = HEADER.map { k ->
            when (k) {
                "time" -> tf.format(Date(epochMs))
                "epoch_ms" -> epochMs.toString()
                else -> cell(values[k])
            }
        }
        File(dir, SessionStore.RECORDS).appendText(row.joinToString(",") + "\n")
    }

    fun read(dir: File): List<Map<String, String>> {
        val f = File(dir, SessionStore.RECORDS)
        if (!f.exists()) return emptyList()
        val lines = f.readLines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        val head = lines.first().split(',')
        return lines.drop(1).map { ln -> val c = ln.split(','); head.indices.associate { head[it] to c.getOrElse(it) { "" } } }
    }

    /** 그래프용 (시각, 수심 — 실패면 NaN) */
    fun points(rows: List<Map<String, String>>): List<Pair<Long, Double>> = rows.mapNotNull { r ->
        val t = r["epoch_ms"]?.toLongOrNull() ?: return@mapNotNull null
        t to (if (r["ok"] == "1") r["depth_mm"]?.toDoubleOrNull() ?: Double.NaN else Double.NaN)
    }
}
