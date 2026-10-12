package io.github.ercadion.pipeflow

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * 정기 기록 보기 — 설정·통계, 수심 그래프, 저장된 사진(수면선·관 내경 겹쳐 표시)
 * 기록은 바꾸지 않음. 진행 중이던 기록이면 '이어서 기록'
 */
class MonitorViewActivity : AppCompatActivity() {
    companion object { const val EXTRA_DIR = "dir" }

    private lateinit var dir: File
    private lateinit var img: OverlayImageView
    private lateinit var txtImage: TextView
    private var rows: List<Map<String, String>> = emptyList()
    private var withImage: List<Int> = emptyList()
    private var pos = 0
    private var scale = 1.0
    private var ell: OverlayImageView.Ell? = null
    private val tf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_monitor_view)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        dir = File(intent.getStringExtra(EXTRA_DIR)!!)
        title = "정기 기록 ${SessionStore.id(dir)}"
        img = findViewById(R.id.imgLast)
        txtImage = findViewById(R.id.txtImage)
        findViewById<Button>(R.id.btnPrev).setOnClickListener { show(pos - 1) }
        findViewById<Button>(R.id.btnNext).setOnClickListener { show(pos + 1) }
        load()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun load() {
        val m = SessionStore.readJson(File(dir, SessionStore.MONITOR)) ?: return
        rows = MonitorLog.read(dir)
        val ok = rows.filter { it["ok"] == "1" }
        val depths = ok.mapNotNull { it["depth_mm"]?.toDoubleOrNull() }
        val im = m.optJSONObject("image")
        if (im != null) {
            val w = im.optInt("width"); val h = im.optInt("height")
            scale = minOf(1.0, m.optInt("thumb_max_side", 640).toDouble() / max(w, h).coerceAtLeast(1))
        }
        m.optJSONObject("ellipse")?.let { e ->
            ell = OverlayImageView.Ell(e.getDouble("cx") * scale, e.getDouble("cy") * scale, e.getDouble("a") * scale,
                e.getDouble("b") * scale, e.getDouble("phi_deg"))
        }
        val status = when (m.optString("status")) {
            "running" -> if (m.optLong("end_ms") > System.currentTimeMillis()) "진행 중 (앱이 꺼져 멈춤)" else "끝남 (끝 시각 지남)"
            "finished" -> "끝남"; "stopped" -> "중지함"; else -> m.optString("status")
        }
        val loc = m.optJSONObject("location")?.let { String.format(Locale.US, "%.6f, %.6f", it.optDouble("lat"), it.optDouble("lon")) } ?: "기록 없음"
        val cal = m.optJSONObject("calibration")
        findViewById<TextView>(R.id.txtSummary).text = String.format(Locale.US,
            "장소 %s · 관로 ID %s\n기간 %s ~ %s · %d분마다 · %s\n기록 %d건 중 성공 %d건\n수심 최소 %s · 최대 %s · 평균 %s mm\n" +
                "기준(시작 때) 수심 %s mm · 관 내경 %.0f mm\n위치 %s · 앱 v%s",
            m.optString("place"), m.optString("pipe_id"),
            tf.format(Date(m.optLong("start_ms"))), tf.format(Date(m.optLong("end_ms"))), m.optInt("interval_min"), status,
            rows.size, ok.size,
            depths.minOrNull()?.let { String.format(Locale.US, "%.1f", it) } ?: "-",
            depths.maxOrNull()?.let { String.format(Locale.US, "%.1f", it) } ?: "-",
            if (depths.isEmpty()) "-" else String.format(Locale.US, "%.1f", depths.average()),
            cal?.let { String.format(Locale.US, "%.1f", it.optDouble("depth_mm")) } ?: "-",
            m.optJSONObject("params")?.optDouble("diameter_mm") ?: Double.NaN, loc, m.optString("app_version"))
        val chart = findViewById<DepthChartView>(R.id.chart)
        chart.tStart = m.optLong("start_ms"); chart.tEnd = minOf(m.optLong("end_ms"), max(System.currentTimeMillis(), m.optLong("start_ms") + 60_000L))
        chart.points = MonitorLog.points(rows)
        withImage = rows.indices.filter { !rows[it]["image"].isNullOrEmpty() }
        show(withImage.size - 1)
        val canResume = m.optString("status") == "running" && m.optLong("end_ms") > System.currentTimeMillis()
        findViewById<Button>(R.id.btnResume).apply {
            visibility = if (canResume) View.VISIBLE else View.GONE
            setOnClickListener {
                startActivity(Intent(this@MonitorViewActivity, MonitorActivity::class.java).putExtra(MonitorActivity.EXTRA_DIR, dir.absolutePath))
                finish()
            }
        }
    }

    /** 저장된 사진 중 i 번째 (수면선·관 내경 겹쳐) */
    private fun show(i: Int) {
        if (withImage.isEmpty()) { txtImage.text = "저장된 사진 없음"; return }
        pos = i.coerceIn(0, withImage.size - 1)
        val r = rows[withImage[pos]]
        val f = File(dir, r["image"] ?: "")
        val bmp = if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
        img.setImageBitmap(bmp)
        img.ellipse = ell
        val wl = listOf("wl_x1", "wl_y1", "wl_x2", "wl_y2").map { r[it]?.toDoubleOrNull() }
        img.waterline = if (wl.all { it != null }) DoubleArray(4) { wl[it]!! * scale } else null
        val depth = if (r["ok"] == "1") "수심 ${r["depth_mm"]} mm" else "실패: ${r["note"]}"
        txtImage.text = "사진 ${pos + 1} / ${withImage.size} · ${r["time"]} · $depth"
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.result_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_delete) {
            AlertDialog.Builder(this)
                .setTitle("이 정기 기록 삭제")
                .setMessage("${SessionStore.id(dir)}\n모든 기록·사진이 지워지고 되돌릴 수 없습니다.")
                .setPositiveButton("삭제") { _, _ ->
                    if (SessionStore.delete(this, dir)) { Toast.makeText(this, "삭제했습니다", Toast.LENGTH_SHORT).show(); finish() }
                    else Toast.makeText(this, "삭제 실패", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("취소", null).show()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
