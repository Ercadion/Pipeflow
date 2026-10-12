package io.github.ercadion.pipeflow

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText

/** 측정 탭: 관 정보·촬영 설정 입력 → 촬영해서 측정 / 정기 기록 (설정은 저장되어 다음에도 그대로) */
class MeasureActivity : AppCompatActivity() {
    private lateinit var editD: TextInputEditText
    private lateinit var editSlope: TextInputEditText
    private lateinit var spinMat: Spinner
    private lateinit var spinTex: Spinner
    private lateinit var spinFps: Spinner
    private lateinit var spinDur: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_measure)
        title = "측정"
        NavBar.setup(this, R.id.nav_measure)
        editD = findViewById(R.id.editDiameter)
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
        editD.setText(fmt(s.diameterMm))
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
        findViewById<Button>(R.id.btnMonitor).setOnClickListener {
            val st = readForm() ?: return@setOnClickListener
            st.save(this)
            startActivity(Intent(this, MonitorActivity::class.java))
        }
    }

    override fun onPause() {
        super.onPause()
        readForm(quiet = true)?.save(this)      // 입력하다 다른 탭으로 가도 남김
    }

    private fun fmt(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    private fun readForm(quiet: Boolean = false): Settings? {
        val d = editD.text?.toString()?.toDoubleOrNull()
        if (d == null || d <= 0) {
            if (!quiet) Toast.makeText(this, "관 내경을 입력하세요", Toast.LENGTH_SHORT).show()
            return null
        }
        return Settings(
            diameterMm = d,
            slopePercent = editSlope.text?.toString()?.toDoubleOrNull(),
            material = Settings.MATERIALS[spinMat.selectedItemPosition].first,
            textureSign = Settings.TEXTURES[spinTex.selectedItemPosition].first,
            fps = Settings.FPS[spinFps.selectedItemPosition],
            durationSec = Settings.DURATIONS[spinDur.selectedItemPosition],
        )
    }
}
