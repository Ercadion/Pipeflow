package io.github.ercadion.pipeflow

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var editD: TextInputEditText
    private lateinit var editWall: TextInputEditText
    private lateinit var editSlope: TextInputEditText
    private lateinit var spinMat: Spinner
    private lateinit var spinTex: Spinner
    private lateinit var spinFps: Spinner
    private lateinit var spinDur: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        editD = findViewById(R.id.editDiameter)
        editWall = findViewById(R.id.editWall)
        editSlope = findViewById(R.id.editSlope)
        spinMat = findViewById(R.id.spinMaterial)
        spinTex = findViewById(R.id.spinTexture)
        spinFps = findViewById(R.id.spinFps)
        spinDur = findViewById(R.id.spinDuration)

        fun adapter(items: List<String>) =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        spinMat.adapter = adapter(Settings.MATERIALS.map { it.second })
        spinTex.adapter = adapter(Settings.TEXTURES.map { it.second })
        spinFps.adapter = adapter(Settings.FPS.map { "목표 $it fps (기기가 지원하지 않으면 낮춤)" })
        spinDur.adapter = adapter(Settings.DURATIONS.map { "촬영 ${it.toInt()}초 (느린 흐름은 길게)" })

        val s = Settings.load(this)
        editD.setText(fmt(s.diameterMm)); editWall.setText(fmt(s.wallMm))
        editSlope.setText(s.slopePercent?.let { fmt(it) } ?: "")
        spinMat.setSelection(Settings.MATERIALS.indexOfFirst { it.first == s.material }.coerceAtLeast(0))
        spinTex.setSelection(Settings.TEXTURES.indexOfFirst { it.first == s.textureSign }.coerceAtLeast(0))
        spinFps.setSelection(Settings.FPS.indexOf(s.fps).coerceAtLeast(0))
        spinDur.setSelection(Settings.DURATIONS.indexOf(s.durationSec).coerceAtLeast(0))

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            val st = readForm() ?: return@setOnClickListener
            st.save(this)
            startActivity(Intent(this, CaptureActivity::class.java))
        }
        findViewById<Button>(R.id.btnHistory).setOnClickListener { showHistory() }
        findViewById<TextView>(R.id.txtInfo).text =
            "버전 ${BuildConfig.VERSION_NAME} · 측정 데이터 저장 위치: ${SessionStore.root(this).absolutePath}"
    }

    private fun fmt(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    private fun readForm(): Settings? {
        val d = editD.text?.toString()?.toDoubleOrNull()
        if (d == null || d <= 0) { Toast.makeText(this, "관 내경을 입력하세요", Toast.LENGTH_SHORT).show(); return null }
        return Settings(
            diameterMm = d,
            wallMm = editWall.text?.toString()?.toDoubleOrNull() ?: 0.0,
            slopePercent = editSlope.text?.toString()?.toDoubleOrNull(),
            material = Settings.MATERIALS[spinMat.selectedItemPosition].first,
            textureSign = Settings.TEXTURES[spinTex.selectedItemPosition].first,
            fps = Settings.FPS[spinFps.selectedItemPosition],
            durationSec = Settings.DURATIONS[spinDur.selectedItemPosition],
        )
    }

    private fun showHistory() {
        val sessions = SessionStore.list(this)
        if (sessions.isEmpty()) { Toast.makeText(this, "측정 기록이 없습니다", Toast.LENGTH_SHORT).show(); return }
        val labels = sessions.map { dir -> describe(dir) }.toTypedArray()
        AlertDialog.Builder(this).setTitle("측정 기록 (${sessions.size})")
            .setItems(labels) { _, i ->
                startActivity(Intent(this, ResultActivity::class.java)
                    .putExtra(ResultActivity.EXTRA_DIR, sessions[i].absolutePath))
            }.setNegativeButton("닫기", null).show()
    }

    private fun describe(dir: File): String {
        val r = SessionStore.readJson(File(dir, "result.json"))
        val lv = r?.optJSONObject("level")
        val st = r?.optJSONObject("velocity_stiv")
        val parts = mutableListOf(dir.name)
        lv?.let { parts += String.format(Locale.US, "h=%.1fmm", it.optDouble("depth_mm")) }
        st?.let { parts += String.format(Locale.US, "v=%.3fm/s", it.optDouble("v_surface_mps")) }
        if (r == null) parts += "(미분석)"
        return parts.joinToString("  ")
    }
}
