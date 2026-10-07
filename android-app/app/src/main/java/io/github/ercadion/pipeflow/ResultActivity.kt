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
import kotlin.math.abs

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
    /** 처음 자동 결과 (result_auto.json) — 사람 수정 전 값. 화면을 다시 열어도 이 파일에서 읽음 */
    @Volatile private var autoResult: JSONObject? = null
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
        migrateLegacy()
        autoResult = SessionStore.readJson(File(dir, "result_auto.json"))

        btnEdit.setOnClickListener {
            img.editMode = !img.editMode
            btnEdit.text = if (img.editMode) "수정 끝" else "수면선 수정"
            if (img.editMode) Toast.makeText(this, "빨간 점을 끌어 수면선 양 끝을 맞추세요", Toast.LENGTH_LONG).show()
        }
        btnRerun.setOnClickListener { run(manual = img.waterline?.clone()) }
        findViewById<Button>(R.id.btnRedetect).setOnClickListener { run(manual = null, redetect = true) }
        findViewById<Button>(R.id.btnGood).setOnClickListener { labelAuto("correct") }
        findViewById<Button>(R.id.btnBad).setOnClickListener { labelAuto("wrong") }
        findViewById<Button>(R.id.btnFinalGood).setOnClickListener { labelFinal("correct") }
        findViewById<Button>(R.id.btnFinalBad).setOnClickListener { labelFinal("wrong") }
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

    private fun run(manual: DoubleArray?, redetect: Boolean = false) {
        if (running) return
        running = true
        img.editMode = false; btnEdit.text = "수면선 수정"
        progress.visibility = View.VISIBLE; btnRerun.isEnabled = false
        txtProgress.text = "계산 준비 중… (처음 실행 시 Python 로딩에 몇 초 걸림)"
        val meta = SessionStore.readJson(File(dir, "meta.json"))
        val params = meta?.optJSONObject("params") ?: Settings.load(this).toParams()
        val kind = when { redetect -> "rim_redetect"; manual != null -> "manual_waterline"; else -> "auto" }
        val before = last
        if (redetect) {
            // 실시간 포착 타원을 쓰지 않고 전체 영상에서 RANSAC 으로 다시 찾기
            params.put("use_live_ellipse", false)
        }
        if (manual != null) {
            params.put("waterline_pts", JSONArray().put(JSONArray(listOf(manual[0], manual[1])))
                .put(JSONArray(listOf(manual[2], manual[3]))))
            // 테두리는 이전 결과 재사용 (빠름)
            last?.optJSONObject("overlay")?.optJSONObject("ellipse")?.let { params.put("ellipse", it) }
        }
        val inputs = JSONObject().put("use_live_ellipse", params.optBoolean("use_live_ellipse", true))
        params.optJSONArray("waterline_pts")?.let { inputs.put("waterline_pts", it) }
        params.optJSONObject("ellipse")?.let { inputs.put("ellipse", it) }
        worker.execute {
            val t0 = System.currentTimeMillis()
            val res = try {
                PyBridge.analyze(this, dir, params) { msg -> runOnUiThread { txtProgress.text = msg } }
            } catch (e: Throwable) {
                JSONObject().put("ok", false).put("error", e.toString())
            }
            record(kind, inputs, before, manual, res)
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
        }
        val sb = StringBuilder()
        val lv = r.getJSONObject("level")
        val edited = differs(autoResult, r)
        findViewById<View>(R.id.boxFinal).visibility = if (edited) View.VISIBLE else View.GONE
        autoResult?.takeIf { edited }?.let { a ->
            val al = a.optJSONObject("level")
            val av = a.optJSONObject("velocity_stiv")
            sb.append(String.format(Locale.US, "□ 처음 자동 결과: 수심 %.1f mm", al?.optDouble("depth_mm") ?: Double.NaN))
            if (av != null) sb.append(String.format(Locale.US, " · 표면유속 %.3f m/s · 유량 %.3f L/s",
                av.optDouble("v_surface_mps"), av.optDouble("Q_Lps")))
            sb.append("  → 아래는 수정 후 결과\n\n")
        }
        val D = lv.optDouble("diameter_mm")
        sb.append(String.format(Locale.US, "■ 수심  %.1f mm  (충만율 %.1f%%, 내경 %.0f mm)\n",
            lv.optDouble("depth_mm"), lv.optDouble("fill_ratio") * 100, D))
        sb.append(String.format(Locale.US, "   계산 방식: %s · 카메라 기울기 %.1f° · 수면선 기울기 %.1f°\n",
            if (lv.optString("method") == "perspective") "완전투시(초점거리 사용)" else "약투시(근사)",
            lv.optDouble("tilt_deg"), lv.optDouble("roll_deg")))
        sb.append("   테두리: ").append(when (lv.optString("rim_source")) {
            "live_seed" -> "촬영 화면에서 포착한 타원을 정밀화"
            "auto_ransac" -> "전체 영상 자동 검출"
            else -> "이전 결과 재사용"
        })
        when (lv.optString("inner_source")) {
            "inner_ring" -> sb.append(" · 내경: 끝단 면 안쪽 테두리")
            "detected_is_inner" -> sb.append(" · 내경: 안쪽 테두리(바깥 테두리 확인)")
            "single_rim" -> sb.append(" · 내경: 테두리 하나(그대로 사용)")
        }
        sb.append("\n")
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

    /** 처음 자동 결과(사람 수정 전)가 맞았는지 */
    private fun labelAuto(v: String) {
        SessionStore.writeLabels(dir) {
            it.put("auto_feedback", v)
            it.put("auto_feedback_ms", System.currentTimeMillis())
            it.put("note", editNote.text?.toString() ?: "")
        }
        Toast.makeText(this, "저장했습니다 (처음 자동 결과: ${if (v == "correct") "맞음" else "틀림"})", Toast.LENGTH_SHORT).show()
    }

    /** 사람이 수정한 최종 결과가 맞는지 — 최종 수면선·테두리·수심을 함께 기록 */
    private fun labelFinal(v: String) {
        val r = last
        SessionStore.writeLabels(dir) {
            it.put("final_feedback", v)
            it.put("final_feedback_ms", System.currentTimeMillis())
            it.put("final_run", SessionStore.runCount(dir))
            it.put("note", editNote.text?.toString() ?: "")
            img.waterline?.let { w -> it.put("final_waterline", JSONArray(w.toList())) }
            r?.optJSONObject("overlay")?.optJSONObject("ellipse")?.let { e -> it.put("final_ellipse", e) }
            r?.optJSONObject("level")?.let { l -> it.put("final_depth_mm", l.optDouble("depth_mm")) }
        }
        Toast.makeText(this, "저장했습니다 (수정한 최종 결과: ${if (v == "correct") "맞음" else "틀림"})", Toast.LENGTH_SHORT).show()
    }

    /** 분석 1회 기록: runs.json 추가, 처음 자동 결과 보존, 사람 수정이면 수정 전/후를 edits 에 */
    private fun record(kind: String, inputs: JSONObject, before: JSONObject?, manual: DoubleArray?, res: JSONObject) {
        if (!res.optBoolean("ok")) {
            SessionStore.appendRun(dir, JSONObject().put("kind", kind).put("time_ms", System.currentTimeMillis())
                .put("inputs", inputs).put("ok", false).put("error", res.optString("error")))
            return
        }
        val idx = SessionStore.appendRun(dir, JSONObject().put("kind", kind).put("time_ms", System.currentTimeMillis())
            .put("inputs", inputs).put("ok", true).put("result", res))
        val autoFile = File(dir, "result_auto.json")
        if (kind == "auto" && !autoFile.exists()) {
            autoFile.writeText(res.toString(1))
            autoResult = res
        }
        val bOv = before?.optJSONObject("overlay")
        val aOv = res.optJSONObject("overlay")
        fun depth(r: JSONObject?) = r?.optJSONObject("level")?.optDouble("depth_mm")
        when (kind) {
            "manual_waterline" -> {
                SessionStore.appendEdit(dir, JSONObject().put("type", "waterline").put("run", idx)
                    .put("changed", differs(before, res))
                    .put("before", bOv?.optJSONArray("waterline")).put("after", aOv?.optJSONArray("waterline"))
                    .put("depth_before_mm", depth(before)).put("depth_after_mm", depth(res)))
                if (differs(autoResult, res)) SessionStore.writeLabels(dir) { lb ->
                    // 학습 정답용: 최신 수동 수면선 (자동 결과와 다를 때만)
                    manual?.let { lb.put("manual_waterline", JSONArray(it.toList())) }
                    autoWaterlineOf(autoResult)?.let { lb.put("auto_waterline", it) }
                } else SessionStore.writeLabels(dir) { lb -> lb.remove("manual_waterline") }   // 자동 수면선으로 되돌림
            }
            "rim_redetect" -> SessionStore.appendEdit(dir, JSONObject().put("type", "rim_redetect").put("run", idx)
                .put("changed", differs(before, res))
                .put("before", bOv?.optJSONObject("ellipse")).put("after", aOv?.optJSONObject("ellipse"))
                .put("depth_before_mm", depth(before)).put("depth_after_mm", depth(res)))
        }
    }

    /** 두 결과의 수면선(0.5 px)·테두리(0.5 px) 가 다르면 true — 사람이 고친 결과인지 판단 */
    private fun differs(a: JSONObject?, b: JSONObject?): Boolean {
        if (a == null || b == null) return false
        val wa = autoWaterlineOf(a); val wb = autoWaterlineOf(b)
        if (wa != null && wb != null) for (i in 0 until 4) if (abs(wa.getDouble(i) - wb.getDouble(i)) > 0.5) return true
        val ea = a.optJSONObject("overlay")?.optJSONObject("ellipse")
        val eb = b.optJSONObject("overlay")?.optJSONObject("ellipse")
        if (ea != null && eb != null) for (k in listOf("cx", "cy", "a", "b"))
            if (abs(ea.optDouble(k) - eb.optDouble(k)) > 0.5) return true
        return false
    }

    private fun autoWaterlineOf(r: JSONObject?): JSONArray? {
        val wl = r?.optJSONObject("overlay")?.optJSONArray("waterline") ?: return null
        return JSONArray(listOf(wl.getJSONArray(0).getDouble(0), wl.getJSONArray(0).getDouble(1),
            wl.getJSONArray(1).getDouble(0), wl.getJSONArray(1).getDouble(1)))
    }

    /** v0.3.3 이전 세션: result_auto.json 이 없으면, 사람 수정 흔적이 없을 때만 지금 result.json 을 자동 결과로 보존 */
    private fun migrateLegacy() {
        val autoFile = File(dir, "result_auto.json")
        if (autoFile.exists()) return
        val cur = SessionStore.readJson(File(dir, "result.json")) ?: return
        if (!cur.optBoolean("ok")) return
        val lb = SessionStore.readJson(File(dir, "labels.json"))
        val touched = lb?.has("manual_waterline") == true || cur.optJSONObject("level")?.optString("rim_source") == "manual_or_previous"
        if (touched) {
            // 수정 전 값은 남아 있지 않음 → 그 사실만 기록 (자동 수면선 좌표는 labels.auto_waterline 에 있을 수 있음)
            if (lb?.has("legacy_auto_result_lost") != true) SessionStore.writeLabels(dir) { it.put("legacy_auto_result_lost", true) }
            return
        }
        autoFile.writeText(cur.toString(1))
        if (SessionStore.runCount(dir) == 0)
            SessionStore.appendRun(dir, JSONObject().put("kind", "auto").put("time_ms", autoFile.lastModified())
                .put("inputs", JSONObject()).put("ok", true).put("result", cur).put("migrated", true))
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
