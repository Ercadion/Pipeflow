package io.github.ercadion.pipeflow

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.textfield.TextInputEditText
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * 측정 결과 화면
 *  - 저장 전 확인 (촬영 직후, .review_pending 있음): 자동 결과 확인 → 틀렸으면 수면선 수정·테두리 재검출 → 저장 / 삭제
 *    고친 결과는 result_corrected.json, 자동 결과(result.json)는 그대로
 *  - 저장된 측정: 결과는 바꿀 수 없음 (보기 전용). 실측값·메모만 나중에 입력 가능 (annotations.json 에 이력으로 덧붙임)
 *  - 고친 측정은 '자동 / 수정 / 비교' 보기 — 비교: 한 화면에 자동(주황 점선)·수정(초록·빨강)을 겹쳐 보이고 수치 표로 비교
 */
class ResultActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_DIR = "dir"
        private const val CORR_TMP = ".corr_tmp.json"     // 엔진이 쓰는 임시 파일 (성공했을 때만 result_corrected.json 으로 확정)
    }

    private enum class View3 { AUTO, CORR, BOTH }

    private lateinit var dir: File
    private lateinit var img: OverlayImageView
    private lateinit var txtMode: TextView
    private lateinit var txtHint: TextView
    private lateinit var txtResult: TextView
    private lateinit var txtProgress: TextView
    private lateinit var progress: ProgressBar
    private lateinit var groupView: RadioGroup
    private lateinit var boxReview: View
    private lateinit var boxSave: View
    private lateinit var btnEdit: Button
    private lateinit var btnRedetect: Button
    private lateinit var btnRevert: Button
    private lateinit var btnSave: Button
    private lateinit var btnExport: Button
    private lateinit var editNote: TextInputEditText
    private lateinit var editTruthDepth: TextInputEditText
    private lateinit var editTruthV: TextInputEditText
    private lateinit var editTruthQ: TextInputEditText

    private val worker = Executors.newSingleThreadExecutor()
    private var review = false
    @Volatile private var auto: JSONObject? = null
    @Volatile private var corr: JSONObject? = null
    private var view = View3.AUTO
    private var running = false
    private var deleted = false
    private var saveAfterRun = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result)
        dir = File(intent.getStringExtra(EXTRA_DIR)!!)
        title = "측정 ${SessionStore.id(dir)}"
        img = findViewById(R.id.imgOverlay)
        txtMode = findViewById(R.id.txtMode)
        txtHint = findViewById(R.id.txtHint)
        txtResult = findViewById(R.id.txtResult)
        txtProgress = findViewById(R.id.txtProgress)
        progress = findViewById(R.id.progress)
        groupView = findViewById(R.id.groupView)
        boxReview = findViewById(R.id.boxReview)
        boxSave = findViewById(R.id.boxSave)
        btnEdit = findViewById(R.id.btnEdit)
        btnRedetect = findViewById(R.id.btnRedetect)
        btnRevert = findViewById(R.id.btnRevert)
        btnSave = findViewById(R.id.btnSave)
        btnExport = findViewById(R.id.btnExport)
        editNote = findViewById(R.id.editNote)
        editTruthDepth = findViewById(R.id.editTruthDepth)
        editTruthV = findViewById(R.id.editTruthV)
        editTruthQ = findViewById(R.id.editTruthQ)

        review = SessionStore.isPending(dir)
        loadStillBitmap()
        SessionStore.annotations(dir).let { a ->
            if (a.has("note")) editNote.setText(a.optString("note"))
            fun put(e: TextInputEditText, k: String) { if (a.has(k)) e.setText(a.optDouble(k).toString()) }
            put(editTruthDepth, "truth_depth_mm"); put(editTruthV, "truth_v_mean_mps"); put(editTruthQ, "truth_Q_Lps")
        }
        auto = SessionStore.autoResult(dir)
        corr = SessionStore.correctedResult(dir)
        view = if (corr != null) View3.BOTH else View3.AUTO

        supportActionBar?.setDisplayHomeAsUpEnabled(true)      // 왼쪽 위 ← 뒤로가기
        onBackPressedDispatcher.addCallback(this, backCallback)
        img.onTapTarget = { kind -> tapInfo(kind) }
        groupView.setOnCheckedChangeListener { _, id ->
            view = when (id) { R.id.viewCorr -> View3.CORR; R.id.viewBoth -> View3.BOTH; else -> View3.AUTO }
            render()
        }
        btnEdit.setOnClickListener { if (img.editMode) finishEdit() else startEdit() }
        btnRedetect.setOnClickListener { correct(manual = currentManual(), redetect = true) }
        btnRevert.setOnClickListener { revert() }
        btnSave.setOnClickListener { save() }
        findViewById<Button>(R.id.btnDiscard).setOnClickListener { confirmDelete(discard = true) }
        btnExport.setOnClickListener { export() }

        if (review && auto == null) analyzeAuto() else render()
    }

    override fun onPause() {
        super.onPause()
        if (!deleted) saveAnnotations()
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
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

    // ------------------------------------------------------------------ 분석 (저장 전 확인 단계에서만)
    private fun baseParams(): JSONObject {
        val meta = SessionStore.readJson(File(dir, "meta.json"))
        val params = JSONObject((meta?.optJSONObject("params") ?: Settings.load(this).toParams()).toString())
        params.put("app_version", BuildConfig.VERSION_NAME)      // 결과에 엔진(앱) 버전 기록
        params.put("app_build", BuildConfig.GIT_SHA)
        return params
    }

    private fun startRun(msg: String) {
        running = true
        img.editMode = false
        progress.visibility = View.VISIBLE
        txtProgress.text = msg
        updateButtons()
    }

    private fun endRun(t0: Long) {
        running = false
        progress.visibility = View.GONE
        txtProgress.text = String.format(Locale.US, "계산 시간 %.1f초", (System.currentTimeMillis() - t0) / 1000.0)
        updateButtons()
    }

    /** 촬영 직후 자동 분석 1회 → result.json (이후 다시 쓰지 않음) */
    private fun analyzeAuto() {
        if (running) return
        startRun("계산 준비 중… (처음 실행 시 Python 로딩에 몇 초 걸림)")
        val params = baseParams().put("result_name", "result.json")
        worker.execute {
            val t0 = System.currentTimeMillis()
            val res = try {
                PyBridge.analyze(this, dir, params) { m -> runOnUiThread { txtProgress.text = m } }
            } catch (e: Throwable) {
                JSONObject().put("ok", false).put("error", e.toString()).also { File(dir, "result.json").writeText(it.toString(1)) }
            }
            runOnUiThread {
                auto = res; endRun(t0); render()
                if (saveAfterRun) { saveAfterRun = false; save() }
            }
        }
    }

    /** 지금까지 고친 수면선 (없으면 null) */
    private fun currentManual(): DoubleArray? =
        corr?.optJSONObject("correction")?.optJSONArray("waterline_pts")?.let { a ->
            doubleArrayOf(a.getJSONArray(0).getDouble(0), a.getJSONArray(0).getDouble(1),
                a.getJSONArray(1).getDouble(0), a.getJSONArray(1).getDouble(1))
        }

    /**
     * 고친 결과 계산 → result_corrected.json (자동 결과는 그대로)
     *  manual: 사람이 맞춘 수면선 (null 이면 자동 검출), redetect: 테두리를 전체 영상에서 다시 검출
     *  이전에 고친 내용(재검출한 테두리, 맞춘 수면선)은 이어서 유지
     */
    private fun correct(manual: DoubleArray?, redetect: Boolean) {
        if (running || !review) return
        val prev = corr
        val prevCorr = prev?.optJSONObject("correction")
        val rimRedetect = redetect || prevCorr?.optBoolean("rim_redetect") == true
        val params = baseParams().put("result_name", CORR_TMP)
        if (redetect) params.put("use_live_ellipse", false)      // 실시간 포착 타원 대신 전체 영상에서 RANSAC
        else (prev ?: auto)?.optJSONObject("overlay")?.optJSONObject("ellipse")?.let { params.put("ellipse", it) }
        manual?.let {
            params.put("waterline_pts", JSONArray().put(JSONArray(listOf(it[0], it[1]))).put(JSONArray(listOf(it[2], it[3]))))
        }
        startRun(if (redetect) "테두리를 다시 검출하는 중…" else "고친 수면선으로 계산 중…")
        worker.execute {
            val t0 = System.currentTimeMillis()
            val res = try {
                PyBridge.analyze(this, dir, params) { m -> runOnUiThread { txtProgress.text = m } }
            } catch (e: Throwable) {
                JSONObject().put("ok", false).put("error", e.toString())
            }
            File(dir, CORR_TMP).delete()
            val ok = res.optBoolean("ok")
            if (ok) {
                res.put("correction", JSONObject()
                    .put("time_ms", System.currentTimeMillis())
                    .put("rim_redetect", rimRedetect)
                    .put("waterline_pts", params.optJSONArray("waterline_pts") ?: JSONObject.NULL)
                    .put("based_on", "result.json"))
                File(dir, "result_corrected.json").writeText(res.toString(1))
            }
            runOnUiThread {
                endRun(t0)
                if (ok) {
                    corr = res; view = View3.BOTH
                } else {
                    Toast.makeText(this, "계산 실패: ${res.optString("error")} (이전 결과 유지)", Toast.LENGTH_LONG).show()
                }
                render()
                if (saveAfterRun) { saveAfterRun = false; if (ok) save() }
            }
        }
    }

    private fun startEdit() {
        if (running) return
        val base = corr ?: auto
        if (base?.optBoolean("ok") != true) {
            Toast.makeText(this, "분석 결과가 없어 수면선을 고칠 수 없습니다. 테두리 재검출을 먼저 해 보세요", Toast.LENGTH_LONG).show()
            return
        }
        // 고칠 대상 = 지금 결과 (고친 적 있으면 고친 결과)
        view = if (corr != null) View3.CORR else View3.AUTO
        img.editMode = true
        btnEdit.text = "수정 끝"
        render()
        Toast.makeText(this, "빨간 점을 끌어 수면선 양 끝을 맞춘 뒤 '수정 끝'을 누르세요", Toast.LENGTH_LONG).show()
    }

    /** 수정 끝: 수면선이 바뀌었으면 고친 수면선으로 계산. 바뀐 게 없으면 true(계산 없음) */
    private fun finishEdit(): Boolean {
        img.editMode = false
        btnEdit.text = "수면선 수정"
        val w = img.waterline?.clone() ?: return true
        val cur = SessionStore.waterlineOf(corr ?: auto)
        val changed = cur == null || (0 until 4).any { abs(cur[it] - w[it]) > 0.5 }
        if (!changed) { render(); return true }
        correct(manual = w, redetect = false)
        return false
    }

    /** 고친 결과 버리고 자동 결과로 */
    private fun revert() {
        if (running || corr == null) return
        AlertDialog.Builder(this)
            .setTitle("자동 결과로 되돌리기")
            .setMessage("고친 수면선·테두리를 버리고 자동 결과로 저장합니다.")
            .setPositiveButton("되돌리기") { _, _ ->
                File(dir, "result_corrected.json").delete()
                corr = null; view = View3.AUTO
                render()
            }
            .setNegativeButton("취소", null).show()
    }

    /** 저장(확정): 이후 결과는 바꿀 수 없음 */
    private fun save() {
        if (!review) return
        if (img.editMode && !finishEdit()) { saveAfterRun = true; return }   // 고치던 수면선 계산 후 저장
        if (running) { saveAfterRun = true; Toast.makeText(this, "계산이 끝나면 저장합니다", Toast.LENGTH_SHORT).show(); return }
        saveAnnotations()
        SessionStore.finalize(dir)
        review = false
        Toast.makeText(this, if (corr != null) "저장했습니다 (자동 결과 + 고친 결과)" else "저장했습니다", Toast.LENGTH_SHORT).show()
        finish()
    }

    // ------------------------------------------------------------------ 화면
    private fun updateButtons() {
        val hasCorr = corr != null
        txtMode.text = when {
            review -> "저장 전 확인 — 자동 결과가 틀렸으면 지금 고치세요. 저장한 뒤에는 결과를 바꿀 수 없습니다 (실측값·메모는 나중에도 입력 가능)."
            else -> savedInfo()
        }
        boxReview.visibility = if (review) View.VISIBLE else View.GONE
        boxSave.visibility = if (review) View.VISIBLE else View.GONE
        btnExport.visibility = if (review) View.GONE else View.VISIBLE
        btnRevert.visibility = if (review && hasCorr) View.VISIBLE else View.GONE
        groupView.visibility = if (hasCorr && !img.editMode) View.VISIBLE else View.GONE
        listOf(btnEdit, btnRedetect, btnRevert, btnSave).forEach { it.isEnabled = !running }
        if (!img.editMode) btnEdit.text = if (hasCorr) "수면선 다시 수정" else "수면선 수정"
        val id = when (view) { View3.AUTO -> R.id.viewAuto; View3.CORR -> R.id.viewCorr; View3.BOTH -> R.id.viewBoth }
        if (groupView.checkedRadioButtonId != id) findViewById<RadioButton>(id).isChecked = true
    }

    private fun savedInfo(): String {
        val rec = SessionStore.readJson(File(dir, "record.json"))
        val sb = StringBuilder("저장된 측정 (결과 변경 불가 · 실측값·메모만 입력 가능)")
        if (rec != null) {
            val t = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(rec.optLong("finalized_ms")))
            sb.append("\n저장 ").append(t).append(" · v").append(rec.optString("app_version"))
            if (rec.optBoolean("corrected")) sb.append(" · 사람이 고침")
        } else if (SessionStore.isLegacy(dir)) sb.append("\n예전 형식(v0.3.5 이전) 측정")
        return sb.toString()
    }

    private fun main(): JSONObject? = if (view == View3.AUTO || corr == null) auto else corr

    private fun setOverlay(r: JSONObject?) {
        val e = r?.optJSONObject("overlay")?.optJSONObject("ellipse")
        img.ellipse = e?.let { OverlayImageView.Ell(it.getDouble("cx"), it.getDouble("cy"), it.getDouble("a"), it.getDouble("b"), it.getDouble("phi_deg")) }
        img.waterline = SessionStore.waterlineOf(r)
    }

    private fun render() {
        updateButtons()
        val m = main()
        if (m == null) {
            img.ellipse = null; img.waterline = null; img.autoWaterline = null; img.autoEllipse = null
            txtResult.text = if (running) "" else "분석 결과가 없습니다"
            return
        }
        setOverlay(if (m.optBoolean("ok")) m else null)
        val both = view == View3.BOTH && corr != null && !img.editMode
        if (both && auto?.optBoolean("ok") == true) {
            val a = auto!!
            img.autoWaterline = SessionStore.waterlineOf(a)
            val ae = a.optJSONObject("overlay")?.optJSONObject("ellipse")
            val ce = corr?.optJSONObject("overlay")?.optJSONObject("ellipse")
            val ellChanged = ae != null && ce != null && listOf("cx", "cy", "a", "b").any { abs(ae.optDouble(it) - ce.optDouble(it)) > 0.5 }
            img.autoEllipse = if (ellChanged) OverlayImageView.Ell(ae!!.getDouble("cx"), ae.getDouble("cy"), ae.getDouble("a"), ae.getDouble("b"), ae.getDouble("phi_deg")) else null
        } else { img.autoWaterline = null; img.autoEllipse = null }

        txtHint.text = when {
            img.editMode -> "빨간 점을 끌어 수면선 양 끝을 맞추세요"
            both -> "비교 — 초록·빨강: 고친 결과 · 주황 점선: 자동 결과 (선을 누르면 수치 표시)"
            view == View3.CORR && corr != null -> "고친 결과 — 초록: 관 내경 · 빨강: 수면선 (선을 누르면 수치 표시)"
            corr != null -> "자동 결과 — 초록: 관 내경 · 빨강: 수면선 (선을 누르면 수치 표시)"
            else -> "초록: 관 내경 · 빨강: 수면선 (선을 누르면 수치 표시)"
        }
        txtResult.text = when {
            !m.optBoolean("ok") -> "분석 실패\n${m.optString("error")}\n\n${m.optString("traceback")}"
            both && auto != null -> compareText(auto!!, corr!!)
            else -> detailText(m)
        }
    }

    private fun lvOf(r: JSONObject?) = r?.optJSONObject("level")
    private fun stOf(r: JSONObject?) = r?.optJSONObject("velocity_stiv")
    private fun fmOf(r: JSONObject?) = r?.optJSONObject("velocity_formula")

    /** 자동 vs 고친 결과 표 */
    private fun compareText(a: JSONObject, c: JSONObject): String {
        val sb = StringBuilder("■ 자동 결과 vs 고친 결과\n")
        fun row(name: String, x: Double?, y: Double?, unit: String, fmt: String, scale: Double = 1.0) {
            val xs = x?.takeIf { !it.isNaN() }?.let { String.format(Locale.US, fmt, it * scale) } ?: "-"
            val ys = y?.takeIf { !it.isNaN() }?.let { String.format(Locale.US, fmt, it * scale) } ?: "-"
            sb.append("   ").append(name).append(": 자동 ").append(xs).append(" → 수정 ").append(ys).append(" ").append(unit)
            if (x != null && y != null && !x.isNaN() && !y.isNaN())
                sb.append(String.format(Locale.US, "  (차이 %+" + fmt.removePrefix("%") + ")", (y - x) * scale))
            sb.append("\n")
        }
        row("수심", lvOf(a)?.optDouble("depth_mm"), lvOf(c)?.optDouble("depth_mm"), "mm", "%.1f")
        row("충만율", lvOf(a)?.optDouble("fill_ratio"), lvOf(c)?.optDouble("fill_ratio"), "%", "%.1f", 100.0)
        row("수면선 기울기", lvOf(a)?.optDouble("roll_deg"), lvOf(c)?.optDouble("roll_deg"), "°", "%.1f")
        row("카메라 기울기", lvOf(a)?.optDouble("tilt_deg"), lvOf(c)?.optDouble("tilt_deg"), "°", "%.1f")
        row("내경", lvOf(a)?.optDouble("diameter_mm"), lvOf(c)?.optDouble("diameter_mm"), "mm", "%.0f")
        row("표면유속", stOf(a)?.optDouble("v_surface_mps"), stOf(c)?.optDouble("v_surface_mps"), "m/s", "%.3f")
        row("평균유속", stOf(a)?.optDouble("v_mean_mps"), stOf(c)?.optDouble("v_mean_mps"), "m/s", "%.3f")
        row("유량(영상)", stOf(a)?.optDouble("Q_Lps"), stOf(c)?.optDouble("Q_Lps"), "L/s", "%.3f")
        row("유량(등류공식)", fmOf(a)?.optDouble("Q_Lps"), fmOf(c)?.optDouble("Q_Lps"), "L/s", "%.3f")
        sb.append("\n   □ 자동 (주황 점선)")
        lvOf(a)?.takeIf { it.has("waterline_confidence") }?.let { sb.append(String.format(Locale.US, " · 수면선 신뢰도 %.2f", it.optDouble("waterline_confidence"))) }
        SessionStore.waterlineOf(a)?.let { sb.append(String.format(Locale.US, "\n      수면선 (%.0f, %.0f) – (%.0f, %.0f) px", it[0], it[1], it[2], it[3])) }
        sb.append("\n   ■ 고친 결과 (초록·빨강)")
        c.optJSONObject("correction")?.let { k ->
            val what = mutableListOf<String>()
            if (!k.isNull("waterline_pts")) what += "수면선 직접 맞춤"
            if (k.optBoolean("rim_redetect")) what += "테두리 재검출"
            if (what.isNotEmpty()) sb.append(" · ").append(what.joinToString(", "))
        }
        SessionStore.waterlineOf(c)?.let { sb.append(String.format(Locale.US, "\n      수면선 (%.0f, %.0f) – (%.0f, %.0f) px", it[0], it[1], it[2], it[3])) }
        sb.append("\n\n'자동' / '수정' 보기에서 각 결과의 자세한 내용을 볼 수 있습니다.")
        return sb.toString()
    }

    private fun detailText(r: JSONObject): String {
        val sb = StringBuilder()
        if (corr != null) sb.append(if (r === corr) "[고친 결과]\n" else "[자동 결과]\n")
        val lv = r.getJSONObject("level")
        val D = lv.optDouble("diameter_mm")
        sb.append(String.format(Locale.US, "■ 수심  %.1f mm  (충만율 %.1f%%, 내경 %.0f mm)\n",
            lv.optDouble("depth_mm"), lv.optDouble("fill_ratio") * 100, D))
        sb.append(String.format(Locale.US, "   계산 방식: %s · 카메라 기울기 %.1f° · 수면선 기울기 %.1f°\n",
            if (lv.optString("method") == "perspective") "완전투시(초점거리 사용)" else "약투시(근사)",
            lv.optDouble("tilt_deg"), lv.optDouble("roll_deg")))
        sb.append("   테두리: ").append(when (lv.optString("rim_source")) {
            "live_seed" -> "촬영 화면에서 포착한 타원을 정밀화"
            "auto_ransac" -> "전체 영상 자동 검출"
            else -> "자동 결과의 테두리 사용"
        })
        when (lv.optString("inner_source")) {
            "inner_ring" -> sb.append(" · 내경: 끝단 면 안쪽 테두리")
            "detected_is_inner" -> sb.append(" · 내경: 안쪽 테두리(바깥 테두리 확인)")
            "single_rim" -> sb.append(" · 내경: 테두리 하나(그대로 사용)")
        }
        sb.append("\n")
        if (lv.has("waterline_confidence"))
            sb.append(String.format(Locale.US, "   수면선 검출 신뢰도 %.2f%s\n", lv.optDouble("waterline_confidence"),
                if (review) " (낮으면 수면선 수정 권장)" else ""))
        r.optJSONObject("velocity_stiv")?.let { s ->
            sb.append(String.format(Locale.US, "\n■ 영상 유속 (H-STIV)\n   표면유속 %.3f m/s → 평균유속 %.3f m/s (×%.2f)\n",
                s.optDouble("v_surface_mps"), s.optDouble("v_mean_mps"), s.optDouble("coef")))
            sb.append(String.format(Locale.US, "   유량 %.3f L/s · 실제 %.1f fps · 프레임 간격 %d · 시선각 %.0f°\n",
                s.optDouble("Q_Lps"), s.optDouble("fps"), s.optInt("stride"), s.optDouble("graze_deg")))
            s.optJSONArray("lines")?.let { lines ->
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
        return sb.toString()
    }

    private fun warnings(sb: StringBuilder, arr: JSONArray?) {
        if (arr == null) return
        for (i in 0 until arr.length()) sb.append("   ⚠ ").append(arr.getString(i)).append("\n")
    }

    /** 이미지에서 선을 눌렀을 때 3초 동안 보일 수치 */
    private fun tapInfo(kind: String): String? {
        val isAuto = kind.startsWith("auto_")
        val r = if (isAuto) auto else main()
        val lv = lvOf(r) ?: return null
        val tag = when {
            isAuto -> "자동 결과, 주황 점선"
            corr != null && r === corr -> "고친 결과"
            corr != null -> "자동 결과"
            else -> ""
        }
        fun head(name: String, color: String) = if (tag.isEmpty()) "$name ($color)" else if (isAuto) "$name ($tag)" else "$name ($color, $tag)"
        return when (kind) {
            "ellipse", "auto_ellipse" -> {
                val e = r?.optJSONObject("overlay")?.optJSONObject("ellipse")
                String.format(Locale.US, "%s\n내경 %.0f mm · 카메라 기울기 %.1f°\n화면 타원 %.0f × %.0f px",
                    head("관 내경", "초록"), lv.optDouble("diameter_mm"), lv.optDouble("tilt_deg"),
                    2 * (e?.optDouble("a") ?: Double.NaN), 2 * (e?.optDouble("b") ?: Double.NaN)) +
                    when (lv.optString("inner_source")) {
                        "inner_ring" -> "\n끝단 면 안쪽 테두리"; "detected_is_inner" -> "\n안쪽 테두리"
                        "single_rim" -> "\n테두리 하나"; else -> "" }
            }
            "waterline", "auto_waterline" -> {
                var t = String.format(Locale.US, "%s\n수심 %.1f mm · 충만율 %.1f%%\n기울기 %.1f°",
                    head("수면선", "빨강"), lv.optDouble("depth_mm"), lv.optDouble("fill_ratio") * 100, lv.optDouble("roll_deg"))
                if (lv.has("waterline_confidence") && (r === auto))
                    t += String.format(Locale.US, " · 신뢰도 %.2f", lv.optDouble("waterline_confidence"))
                t
            }
            else -> null
        }
    }

    // ------------------------------------------------------------------ 뒤로가기·메뉴
    override fun onSupportNavigateUp(): Boolean { onBackPressedDispatcher.onBackPressed(); return true }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when {
                img.editMode -> finishEdit()
                running -> Toast.makeText(this@ResultActivity, "계산 중입니다. 끝난 뒤 나가세요", Toast.LENGTH_SHORT).show()
                review -> AlertDialog.Builder(this@ResultActivity)
                    .setTitle("아직 저장하지 않았습니다")
                    .setMessage("이 측정을 저장할까요? 저장한 뒤에는 결과를 고칠 수 없습니다.")
                    .setPositiveButton("저장") { _, _ -> save() }
                    .setNegativeButton("삭제") { _, _ -> confirmDelete(discard = true) }
                    .setNeutralButton("계속 확인", null).show()
                else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.result_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_delete) { confirmDelete(discard = false); return true }
        return super.onOptionsItemSelected(item)
    }

    /** 이 측정 삭제 (원본·결과·실측값 모두, 확인 후) */
    private fun confirmDelete(discard: Boolean) {
        if (running) { Toast.makeText(this, "계산이 끝난 뒤 삭제하세요", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this)
            .setTitle(if (discard) "저장하지 않고 삭제" else "이 측정 삭제")
            .setMessage("${SessionStore.id(dir)}\n원본 영상·결과·실측값이 모두 지워지고 되돌릴 수 없습니다.")
            .setPositiveButton("삭제") { _, _ ->
                deleted = true
                if (SessionStore.delete(this, dir)) {
                    Toast.makeText(this, "삭제했습니다", Toast.LENGTH_SHORT).show(); finish()
                } else { deleted = false; Toast.makeText(this, "삭제 실패", Toast.LENGTH_SHORT).show() }
            }
            .setNegativeButton("취소", null).show()
    }

    // ------------------------------------------------------------------ 실측값·내보내기
    /** 실측값·메모 → annotations.json (바뀐 값만 이력으로 덧붙임, 비우면 지움 기록) */
    private fun saveAnnotations() {
        fun num(e: TextInputEditText) = e.text?.toString()?.trim()?.replace(',', '.')?.toDoubleOrNull()
        val note = editNote.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        SessionStore.appendAnnotations(dir, mapOf(
            "truth_depth_mm" to num(editTruthDepth),
            "truth_v_mean_mps" to num(editTruthV),
            "truth_Q_Lps" to num(editTruthQ),
            "note" to note))
    }

    private fun export() {
        saveAnnotations()
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
