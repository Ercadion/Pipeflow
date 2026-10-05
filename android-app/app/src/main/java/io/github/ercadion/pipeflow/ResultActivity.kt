package io.github.ercadion.pipeflow

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.textfield.TextInputEditText
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class ResultActivity : AppCompatActivity() {
    companion object { const val EXTRA_DIR = "dir" }

    private lateinit var dir: File
    private lateinit var img: OverlayImageView
    private lateinit var txtResult: TextView
    private lateinit var txtProgress: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnEdit: Button
    private lateinit var btnRerun: Button
    private lateinit var editNote: TextInputEditText
    private val worker = Executors.newSingleThreadExecutor()
    private var last: JSONObject? = null
    private var autoWaterline: DoubleArray? = null
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result)
        dir = File(intent.getStringExtra(EXTRA_DIR)!!)
        title = "측정 ${dir.name}"
        img = findViewById(R.id.imgOverlay)
        txtResult = findViewById(R.id.txtResult)
        txtProgress = findViewById(R.id.txtProgress)
        progress = findViewById(R.id.progress)
        btnEdit = findViewById(R.id.btnEdit)
        btnRerun = findViewById(R.id.btnRerun)
        editNote = findViewById(R.id.editNote)

        loadStillBitmap()
        SessionStore.readJson(File(dir, "labels.json"))?.optString("note")?.let { editNote.setText(it) }

        btnEdit.setOnClickListener {
            img.editMode = !img.editMode
            btnEdit.text = if (img.editMode) "수정 끝" else "수면선 수정"
            if (img.editMode) Toast.makeText(this, "빨간 점을 끌어 수면선 양 끝을 맞추세요", Toast.LENGTH_LONG).show()
        }
        btnRerun.setOnClickListener { run(manual = img.waterline?.clone()) }
        findViewById<Button>(R.id.btnGood).setOnClickListener { label("correct") }
        findViewById<Button>(R.id.btnBad).setOnClickListener { label("wrong") }
        findViewById<Button>(R.id.btnExport).setOnClickListener { export() }

        val prev = SessionStore.readJson(File(dir, "result.json"))
        if (prev != null && prev.optBoolean("ok")) show(prev) else run(null)
    }

    override fun onPause() {
        super.onPause()
        val note = editNote.text?.toString() ?: ""
        if (note.isNotEmpty()) SessionStore.writeLabels(dir) { it.put("note", note) }
    }

    /** still.y (Y 평면) → 회전 → 회색조 Bitmap */
    private fun loadStillBitmap() {
        val meta = SessionStore.readJson(File(dir, "meta.json")) ?: return
        val st = meta.getJSONObject("still")
        val w = st.getInt("width"); val h = st.getInt("height")
        val bytes = File(dir, st.getString("file")).readBytes()
        val px = IntArray(w * h)
        for (i in 0 until w * h) {
            val v = bytes[i].toInt() and 0xFF
            px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        var bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        val rot = meta.optInt("rotation_degrees", 0)
        if (rot != 0) {
            val m = Matrix().apply { postRotate(rot.toFloat()) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, w, h, m, true)
        }
        img.setImageBitmap(bmp)
    }

    private fun run(manual: DoubleArray?) {
        if (running) return
        running = true
        img.editMode = false; btnEdit.text = "수면선 수정"
        progress.visibility = View.VISIBLE; btnRerun.isEnabled = false
        txtProgress.text = "계산 준비 중… (처음 실행 시 Python 로딩에 몇 초 걸림)"
        val meta = SessionStore.readJson(File(dir, "meta.json"))
        val params = meta?.optJSONObject("params") ?: Settings.load(this).toParams()
        if (manual != null) {
            params.put("waterline_pts", JSONArray().put(JSONArray(listOf(manual[0], manual[1])))
                .put(JSONArray(listOf(manual[2], manual[3]))))
            // 테두리는 이전 결과 재사용 (빠름)
            last?.optJSONObject("overlay")?.optJSONObject("ellipse")?.let { params.put("ellipse", it) }
            SessionStore.writeLabels(dir) { lb ->
                lb.put("manual_waterline", JSONArray(manual.toList()))
                autoWaterline?.let { lb.put("auto_waterline", JSONArray(it.toList())) }
            }
        }
        worker.execute {
            val t0 = System.currentTimeMillis()
            val res = try {
                PyBridge.analyze(this, dir, params) { msg -> runOnUiThread { txtProgress.text = msg } }
            } catch (e: Throwable) {
                JSONObject().put("ok", false).put("error", e.toString())
            }
            runOnUiThread {
                running = false
                progress.visibility = View.GONE; btnRerun.isEnabled = true
                txtProgress.text = String.format(Locale.US, "계산 시간 %.1f초", (System.currentTimeMillis() - t0) / 1000.0)
                show(res)
            }
        }
    }

    private fun show(r: JSONObject) {
        last = r
        progress.visibility = View.GONE
        if (!r.optBoolean("ok")) {
            txtResult.text = "분석 실패\n${r.optString("error")}\n\n${r.optString("traceback")}"
            return
        }
        r.optJSONObject("overlay")?.let { ov ->
            val e = ov.getJSONObject("ellipse")
            img.ellipse = OverlayImageView.Ell(e.getDouble("cx"), e.getDouble("cy"), e.getDouble("a"),
                e.getDouble("b"), e.getDouble("phi_deg"))
            val wl = ov.getJSONArray("waterline")
            val arr = doubleArrayOf(wl.getJSONArray(0).getDouble(0), wl.getJSONArray(0).getDouble(1),
                wl.getJSONArray(1).getDouble(0), wl.getJSONArray(1).getDouble(1))
            img.waterline = arr
            if (autoWaterline == null) autoWaterline = arr.clone()
        }
        val sb = StringBuilder()
        val lv = r.getJSONObject("level")
        val D = lv.optDouble("diameter_mm")
        sb.append(String.format(Locale.US, "■ 수심  %.1f mm  (충만율 %.1f%%, 내경 %.0f mm)\n",
            lv.optDouble("depth_mm"), lv.optDouble("fill_ratio") * 100, D))
        sb.append(String.format(Locale.US, "   계산 방식: %s · 카메라 기울기 %.1f° · 수면선 기울기 %.1f°\n",
            if (lv.optString("method") == "perspective") "완전투시(초점거리 사용)" else "약투시(근사)",
            lv.optDouble("tilt_deg"), lv.optDouble("roll_deg")))
        if (lv.has("waterline_confidence"))
            sb.append(String.format(Locale.US, "   수면선 검출 신뢰도 %.2f (낮으면 수동 수정 권장)\n", lv.optDouble("waterline_confidence")))
        r.optJSONObject("velocity_stiv")?.let { s ->
            sb.append(String.format(Locale.US, "\n■ 영상 유속 (H-STIV)\n   표면유속 %.3f m/s → 평균유속 %.3f m/s (×%.2f)\n",
                s.optDouble("v_surface_mps"), s.optDouble("v_mean_mps"), s.optDouble("coef")))
            sb.append(String.format(Locale.US, "   유량 %.3f L/s · 실제 %.1f fps · 프레임 간격 %d · 시선각 %.0f°\n",
                s.optDouble("Q_Lps"), s.optDouble("fps"), s.optInt("stride"), s.optDouble("graze_deg")))
            val lines = s.optJSONArray("lines")
            if (lines != null) {
                sb.append("   폭 방향 분포: ")
                for (i in 0 until lines.length()) {
                    val l = lines.getJSONObject(i)
                    sb.append(String.format(Locale.US, "%.3f(%s) ", l.optDouble("v_mps"), l.optString("used")))
                }
                sb.append("\n")
            }
            warnings(sb, s.optJSONArray("warnings"))
        }
        r.optJSONObject("velocity_formula")?.let { f ->
            sb.append(String.format(Locale.US, "\n■ 등류 공식 유속 (%s)\n   평균유속 %.3f m/s · 유량 %.3f L/s · Re %.0f · Fr %.2f\n",
                f.optString("regime"), f.optDouble("V_mean_mps"), f.optDouble("Q_Lps"), f.optDouble("Re"), f.optDouble("Fr")))
            warnings(sb, f.optJSONArray("warnings"))
        }
        warnings(sb, lv.optJSONArray("warnings"))
        warnings(sb, r.optJSONArray("warnings"))
        txtResult.text = sb.toString()
    }

    private fun warnings(sb: StringBuilder, arr: JSONArray?) {
        if (arr == null) return
        for (i in 0 until arr.length()) sb.append("   ⚠ ").append(arr.getString(i)).append("\n")
    }

    private fun label(v: String) {
        SessionStore.writeLabels(dir) {
            it.put("feedback", v)
            it.put("note", editNote.text?.toString() ?: "")
            img.waterline?.let { w -> it.put("final_waterline", JSONArray(w.toList())) }
        }
        Toast.makeText(this, "저장했습니다 (${if (v == "correct") "맞음" else "틀림"})", Toast.LENGTH_SHORT).show()
    }

    private fun export() {
        SessionStore.writeLabels(dir) { it.put("note", editNote.text?.toString() ?: "") }
        worker.execute {
            val zip = SessionStore.zip(this, dir)
            val uri = FileProvider.getUriForFile(this, "$packageName.files", zip)
            runOnUiThread {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "측정 데이터 공유"))
            }
        }
    }
}
