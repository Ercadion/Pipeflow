package io.github.ercadion.pipeflow

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * 홈 탭: 소개, 멈춘 정기 기록(이어서 기록), 최근 측정 3건, 빠른 시작(측정 · 정기 기록)
 * 하단 탭: 홈 · 측정(MeasureActivity) · 기록(DataActivity) · 설정(SettingsActivity)
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        NavBar.setup(this, R.id.nav_home)
        findViewById<View>(R.id.txtAll).setOnClickListener { startActivity(Intent(this, DataActivity::class.java)) }
        findViewById<View>(R.id.tileMeasure).setOnClickListener { startActivity(Intent(this, MeasureActivity::class.java)) }
        findViewById<View>(R.id.tileMonitor).setOnClickListener { startActivity(Intent(this, MonitorActivity::class.java)) }
        findViewById<TextView>(R.id.txtInfo).text = "버전 ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})"
    }

    override fun onResume() {
        super.onResume()
        NavBar.setup(this, R.id.nav_home)        // 다른 탭에서 돌아왔을 때 홈 표시
        showRunning()
        showRecent()
    }

    /** 앱이 꺼져 멈춘 정기 기록 → 눌러서 이어서 기록 */
    private fun showRunning() {
        val box = findViewById<View>(R.id.boxRunning)
        val d = SessionStore.runningMonitor(this)
        if (d == null) { box.visibility = View.GONE; return }
        val m = SessionStore.readJson(File(d, SessionStore.MONITOR))
        findViewById<TextView>(R.id.txtRunning).text =
            "정기 기록이 멈춰 있습니다 · ${m?.optString("place")} / ${m?.optString("pipe_id")}\n눌러서 이어서 기록 (멈춘 동안은 '기록 못 함' 으로 남음)"
        box.visibility = View.VISIBLE
        box.setOnClickListener {
            startActivity(Intent(this, MonitorActivity::class.java).putExtra(MonitorActivity.EXTRA_DIR, d.absolutePath))
        }
    }

    /** 최근 측정 3건 (카드 누르면 결과 / 정기 기록 보기) */
    private fun showRecent() {
        val box = findViewById<LinearLayout>(R.id.recentList)
        box.removeAllViews()
        val recent = SessionStore.list(this).take(3)
        findViewById<View>(R.id.txtEmpty).visibility = if (recent.isEmpty()) View.VISIBLE else View.GONE
        val inf = LayoutInflater.from(this)
        for (d in recent) {
            val s = SessionSummary.of(d)
            val v = inf.inflate(R.layout.item_recent, box, false)
            v.findViewById<TextView>(R.id.txtDate).text = s.date
            v.findViewById<TextView>(R.id.txtTitle).text = s.title
            v.findViewById<TextView>(R.id.txtLine).text = s.line
            v.findViewById<TextView>(R.id.txtFlags).apply {
                text = s.flags.joinToString(" · ")
                visibility = if (s.flags.isEmpty()) View.GONE else View.VISIBLE
            }
            ThumbCache.load(this, d, v.findViewById<ImageView>(R.id.imgThumb))
            v.setOnClickListener {
                if (s.monitor) startActivity(Intent(this, MonitorViewActivity::class.java).putExtra(MonitorViewActivity.EXTRA_DIR, d.absolutePath))
                else startActivity(Intent(this, ResultActivity::class.java).putExtra(ResultActivity.EXTRA_DIR, d.absolutePath))
            }
            box.addView(v)
        }
    }
}
