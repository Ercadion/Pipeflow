package io.github.ercadion.pipeflow

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * 정기 기록 (고정 설치) — 카메라를 고정해 두고 정한 기간 동안 1·5·10분마다 수위(수심)를 기록
 *  1. 준비: 장소·관로 ID, 기간(시작~끝 날짜·시각), 간격, 조명 → '기준 사진 찍고 관 찾기'
 *  2. 기준 확인: 찾은 관 내경 테두리(초록)·수면선(빨강) 확인 → 시작 (다시 찍기 / 다시 찾기)
 *  3. 기록 중: 화면을 어둡게, 정한 시각마다 사진 5장을 평균해 같은 테두리로 수심 계산 → records.csv + img/
 *     (테두리를 고정해 매번 같은 방식으로 계산 → 오차가 있어도 일관됨, 경향성 확인용)
 *  - 앱이 화면에 있어야 기록됨 (충전기 연결 권장). 나갔다 오면 그동안의 시각은 '기록 못 함' 으로 남김
 *  - 앱이 꺼졌으면 첫 화면에서 '이어서 기록' (EXTRA_DIR)
 * 폴더: sessions/<날짜>/<시각>_<장소>_<관로ID>_정기_v<버전>/  monitor.json · calib.y · records.csv · img/ 폴더의 jpg
 */
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
class MonitorActivity : AppCompatActivity(), SensorEventListener {
    companion object {
        const val EXTRA_DIR = "dir"
        val INTERVALS = listOf(1, 5, 10)
        private const val AVG_FRAMES = 5           // 기록 1번 = 사진 5장 평균 (잔물결·잡음 완화)
        private const val TORCH_WARMUP_MS = 1500L  // 조명 켠 뒤 노출이 맞을 때까지
        private const val GRAB_TIMEOUT_MS = 15_000L
        private const val THUMB = 640              // 저장 사진 긴 변 (px)
    }

    private enum class Stage { SETUP, CALIB, RUN }

    private class Grab(val n: Int, val gapMs: Long, val cb: (List<ByteArray>, Int, Int, Int) -> Unit) {
        val frames = ArrayList<ByteArray>()
        var last = 0L
    }

    private lateinit var previewView: PreviewView
    private lateinit var focusMark: View
    private lateinit var imgCalib: OverlayImageView
    private lateinit var txtTop: TextView
    private lateinit var boxSetup: View
    private lateinit var boxCalib: View
    private lateinit var boxRun: View
    private lateinit var editPlace: TextInputEditText
    private lateinit var editPipe: TextInputEditText
    private lateinit var btnStartTime: Button
    private lateinit var btnEndTime: Button
    private lateinit var spinInterval: Spinner
    private lateinit var chkTorch: CheckBox
    private lateinit var btnFocusClear: Button
    private lateinit var btnCalib: Button
    private lateinit var txtSetupInfo: TextView
    private lateinit var txtCalib: TextView
    private lateinit var btnBegin: Button
    private lateinit var progress: ProgressBar
    private lateinit var txtRunTitle: TextView
    private lateinit var txtRun: TextView
    private lateinit var chart: DepthChartView

    private lateinit var settings: Settings
    private lateinit var sensorManager: SensorManager
    private lateinit var locTracker: LocationTracker
    private val grav = DoubleArray(3)
    private val worker = Executors.newSingleThreadExecutor()
    private val camExec = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var camera: Camera? = null
    private var stage = Stage.SETUP

    // 준비
    private var startMs = 0L
    private var endMs = 0L
    private var intervalMin = 5
    private var torchOnRecord = false
    private var zoom = 1f
    private var hint: DoubleArray? = null           // 탭한 위치 (회전 보정 영상 좌표) → 관 찾기 도움

    // 사진 가져오기
    @Volatile private var grab: Grab? = null
    @Volatile private var frameW = 0
    @Volatile private var frameH = 0
    @Volatile private var frameRot = 0

    // 기준
    private var calibY: ByteArray? = null
    private var calibW = 0
    private var calibH = 0
    private var calibRot = 0
    private var calib: JSONObject? = null
    private var attempt = 0
    private var intrinsics: JSONObject? = null
    private var gravityAtCalib: JSONArray? = null

    // 기록 중
    private var dir: File? = null
    private var monitor: JSONObject? = null
    private var lastSlot = -1L
    private var nextAt = 0L
    private var busy = false
    private var nOk = 0
    private var nFail = 0
    private var lastResult = "-"
    private val tick = Runnable { onTick() }
    private val dim = Runnable { setBright(false) }
    private var grabTimeout: Runnable? = null

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else { Toast.makeText(this, "카메라 권한이 필요합니다", Toast.LENGTH_LONG).show(); finish() }
    }

    private val tf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_monitor)
        title = "정기 기록 (고정 설치)"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        previewView = findViewById(R.id.previewView)
        focusMark = findViewById(R.id.focusMark)
        imgCalib = findViewById(R.id.imgCalib)
        txtTop = findViewById(R.id.txtTop)
        boxSetup = findViewById(R.id.boxSetup)
        boxCalib = findViewById(R.id.boxCalib)
        boxRun = findViewById(R.id.boxRun)
        editPlace = findViewById(R.id.editPlace)
        editPipe = findViewById(R.id.editPipe)
        btnStartTime = findViewById(R.id.btnStartTime)
        btnEndTime = findViewById(R.id.btnEndTime)
        spinInterval = findViewById(R.id.spinInterval)
        chkTorch = findViewById(R.id.chkTorch)
        btnFocusClear = findViewById(R.id.btnFocusClear)
        btnCalib = findViewById(R.id.btnCalib)
        txtSetupInfo = findViewById(R.id.txtSetupInfo)
        txtCalib = findViewById(R.id.txtCalib)
        btnBegin = findViewById(R.id.btnBegin)
        progress = findViewById(R.id.progress)
        txtRunTitle = findViewById(R.id.txtRunTitle)
        txtRun = findViewById(R.id.txtRun)
        chart = findViewById(R.id.chart)
        chart.dark = true

        settings = Settings.load(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locTracker = LocationTracker(this)

        spinInterval.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            INTERVALS.map { "${it}분마다" })
        spinInterval.setSelection(1)
        val c = Calendar.getInstance().apply { add(Calendar.MINUTE, 1); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        startMs = c.timeInMillis
        endMs = startMs + 24 * 3_600_000L
        showTimes()
        editPlace.setText(SessionStore.lastValue(this, "place"))
        editPipe.setText(SessionStore.lastValue(this, "pipe_id"))
        txtSetupInfo.text = String.format(Locale.US,
            "관 내경 %.0f mm (첫 화면 설정). 폰을 고정하고 관 끝단이 화면 가운데 크게 보이게 하세요. " +
                "관 안쪽을 탭하면 그곳에 초점을 맞추고 관 찾기에 참고합니다.", settings.diameterMm)

        btnStartTime.setOnClickListener { pickTime(startMs) { startMs = it; if (endMs <= startMs) endMs = startMs + 3_600_000L; showTimes() } }
        btnEndTime.setOnClickListener { pickTime(endMs) { endMs = it; showTimes() } }
        btnFocusClear.setOnClickListener { clearFocus() }
        btnCalib.setOnClickListener { takeCalib() }
        findViewById<Button>(R.id.btnRetake).setOnClickListener { toSetup() }
        findViewById<Button>(R.id.btnRedetect).setOnClickListener { attempt++; runCalib() }
        btnBegin.setOnClickListener { begin() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { confirmStop() }
        boxRun.setOnClickListener { setBright(true); handler.removeCallbacks(dim); handler.postDelayed(dim, 20_000L) }
        setupGestures()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (stage) {
                    Stage.RUN -> confirmStop()
                    Stage.CALIB -> toSetup()
                    Stage.SETUP -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })

        intent.getStringExtra(EXTRA_DIR)?.let { resume(File(it)) }
        if (stage == Stage.SETUP) toSetup()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        (sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER))
            ?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        locTracker.start()
        if (stage == Stage.RUN) scheduleNext()        // 나갔던 동안의 시각은 '기록 못 함' 으로
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        locTracker.stop()
        if (stage == Stage.RUN) {
            handler.removeCallbacks(tick)
            grabTimeout?.let { handler.removeCallbacks(it) }
            if (grab != null) {            // 사진을 기다리던 중이면 취소 (계산 중이면 그대로 끝나게 둠)
                grab = null; busy = false
                if (torchOnRecord) camera?.cameraControl?.enableTorch(false)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        worker.shutdown(); camExec.shutdown()
    }

    override fun onSensorChanged(e: SensorEvent) {
        for (i in 0..2) grav[i] = 0.9 * grav[i] + 0.1 * e.values[i]
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    // ------------------------------------------------------------------ 기간 선택
    private fun showTimes() {
        btnStartTime.text = "시작\n${tf.format(Date(startMs))}"
        btnEndTime.text = "끝\n${tf.format(Date(endMs))}"
    }

    /** 날짜 → 시각 순서로 고르기 */
    private fun pickTime(cur: Long, done: (Long) -> Unit) {
        val c = Calendar.getInstance().apply { timeInMillis = cur }
        DatePickerDialog(this, { _, y, m, d ->
            TimePickerDialog(this, { _, hh, mm ->
                val r = Calendar.getInstance().apply { set(y, m, d, hh, mm, 0); set(Calendar.MILLISECOND, 0) }
                done(r.timeInMillis)
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    // ------------------------------------------------------------------ 카메라
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val prov = future.get()
            val res = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
            val preview = Preview.Builder().setResolutionSelector(
                ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build())
                .build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder().setResolutionSelector(res)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build().also { it.setAnalyzer(camExec, ::analyze) }
            try {
                prov.unbindAll()
                val cam = prov.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                camera = cam
                cam.cameraControl.setZoomRatio(zoom)
                if (!cam.cameraInfo.hasFlashUnit()) { chkTorch.isChecked = false; chkTorch.isEnabled = false }
            } catch (e: Exception) {
                Toast.makeText(this, "카메라 시작 실패: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** analyzer 스레드: 요청이 있을 때만 Y 평면 복사 */
    private fun analyze(image: ImageProxy) {
        try {
            frameW = image.width; frameH = image.height; frameRot = image.imageInfo.rotationDegrees
            val g = grab ?: return
            val now = SystemClock.elapsedRealtime()
            if (now - g.last < g.gapMs) return
            g.last = now
            g.frames.add(copyY(image))
            if (g.frames.size >= g.n) {
                grab = null
                val fs = ArrayList(g.frames); val w = image.width; val h = image.height; val r = frameRot
                runOnUiThread { g.cb(fs, w, h, r) }
            }
        } catch (_: Exception) {
        } finally {
            image.close()
        }
    }

    private fun copyY(image: ImageProxy): ByteArray {
        val w = image.width; val h = image.height
        val plane = image.planes[0]
        val buf = plane.buffer
        val rs = plane.rowStride; val ps = plane.pixelStride
        val out = ByteArray(w * h)
        buf.rewind()
        if (ps == 1) {
            for (y in 0 until h) { buf.position(y * rs); buf.get(out, y * w, w) }
        } else {
            for (y in 0 until h) for (x in 0 until w) out[y * w + x] = buf.get(y * rs + x * ps)
        }
        return out
    }

    private fun average(fs: List<ByteArray>): ByteArray {
        val n = fs[0].size
        val acc = IntArray(n)
        for (f in fs) for (i in 0 until n) acc[i] += f[i].toInt() and 0xFF
        val k = fs.size
        return ByteArray(n) { ((acc[it] + k / 2) / k).toByte() }
    }

    /** Y 평면 → 회전 보정한 회색조 Bitmap (maxSide 가 있으면 축소) */
    private fun yToBitmap(y: ByteArray, w: Int, h: Int, rot: Int, maxSide: Int? = null): Bitmap {
        val px = IntArray(w * h)
        for (i in 0 until w * h) { val v = y[i].toInt() and 0xFF; px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        val m = Matrix()
        if (rot != 0) m.postRotate(rot.toFloat())
        if (maxSide != null) { val s = maxSide.toFloat() / max(w, h); if (s < 1f) m.postScale(s, s) }
        return Bitmap.createBitmap(bmp, 0, 0, w, h, m, true)
    }

    private fun intrinsicsFor(w: Int, h: Int): JSONObject? = try {
        val cam = camera
        if (cam == null) null else {
            val id = Camera2CameraInfo.from(cam.cameraInfo).cameraId
            val ch = (getSystemService(Context.CAMERA_SERVICE) as CameraManager).getCameraCharacteristics(id)
            CameraIntrinsics.compute(ch, w, h, zoom.toDouble())
        }
    } catch (_: Exception) { null }

    // ------------------------------------------------------------------ 확대·초점 (준비 단계)
    private fun setupGestures() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean { setZoom(zoom * d.scaleFactor); return true }
        })
        val gest = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { tapFocus(e.x, e.y); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean { setZoom(1f); return true }
            override fun onLongPress(e: MotionEvent) { clearFocus() }
        })
        previewView.setOnTouchListener { v, ev ->
            if (stage != Stage.SETUP) return@setOnTouchListener false
            scale.onTouchEvent(ev)
            if (!scale.isInProgress) gest.onTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP) v.performClick()
            true
        }
    }

    private fun setZoom(z: Float) {
        val cam = camera ?: return
        val zs = cam.cameraInfo.zoomState.value ?: return
        zoom = z.coerceIn(zs.minZoomRatio, zs.maxZoomRatio)
        cam.cameraControl.setZoomRatio(zoom)
        txtTop.text = String.format(Locale.US, "배율 %.1f× · 관 안쪽을 탭해 초점 · 두 손가락 확대/축소 · 두 번 탭 1×", zoom)
        if (hint != null) clearFocus()       // 배율이 바뀌면 탭 위치가 달라짐
    }

    /** 탭: 좁은 범위(화면의 6%)에 초점·노출 고정 + 관 찾기 참고 위치 */
    private fun tapFocus(x: Float, y: Float) {
        val cam = camera ?: return
        val pt = previewView.meteringPointFactory.createPoint(x, y, 0.06f)
        cam.cameraControl.startFocusAndMetering(
            FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE).disableAutoCancel().build())
        val half = 18 * resources.displayMetrics.density       // 표시 원 36dp
        focusMark.visibility = View.VISIBLE
        focusMark.x = x - half
        focusMark.y = y - half
        hint = viewToImage(x, y)
        btnFocusClear.isEnabled = true
    }

    private fun clearFocus() {
        camera?.cameraControl?.cancelFocusAndMetering()
        focusMark.visibility = View.GONE
        hint = null
        btnFocusClear.isEnabled = false
    }

    /** 미리보기(FILL_CENTER) 화면 좌표 → 회전 보정한 분석 영상 좌표 */
    private fun viewToImage(x: Float, y: Float): DoubleArray? {
        if (frameW == 0) return null
        val upW = if (frameRot % 180 == 0) frameW else frameH
        val upH = if (frameRot % 180 == 0) frameH else frameW
        val s = max(previewView.width.toDouble() / upW, previewView.height.toDouble() / upH)
        val ox = (previewView.width - upW * s) / 2; val oy = (previewView.height - upH * s) / 2
        return doubleArrayOf((x - ox) / s, (y - oy) / s)
    }

    // ------------------------------------------------------------------ 기준 사진 · 관 찾기
    private fun toSetup() {
        stage = Stage.SETUP
        boxSetup.visibility = View.VISIBLE; boxCalib.visibility = View.GONE; boxRun.visibility = View.GONE
        imgCalib.visibility = View.GONE
        txtTop.text = String.format(Locale.US, "배율 %.1f× · 관 안쪽을 탭해 초점 · 두 손가락 확대/축소 · 두 번 탭 1×", zoom)
        setBright(true)
    }

    private fun setBusy(on: Boolean, msg: String?) {
        progress.visibility = if (on) View.VISIBLE else View.GONE
        btnCalib.isEnabled = !on
        btnBegin.isEnabled = !on && calib?.optBoolean("ok") == true
        msg?.let { txtTop.text = it }
    }

    private fun takeCalib() {
        if (editPlace.text.isNullOrBlank() || editPipe.text.isNullOrBlank()) {
            if (editPlace.text.isNullOrBlank()) editPlace.error = "측정 장소를 입력하세요"
            if (editPipe.text.isNullOrBlank()) editPipe.error = "관로 ID를 입력하세요"
            return
        }
        if (camera == null) { Toast.makeText(this, "카메라 준비 중입니다", Toast.LENGTH_SHORT).show(); return }
        setBusy(true, "기준 사진 찍는 중… 폰을 움직이지 마세요")
        grab = Grab(AVG_FRAMES, 120L) { fs, w, h, rot ->
            calibY = average(fs); calibW = w; calibH = h; calibRot = rot
            intrinsics = intrinsicsFor(w, h)
            gravityAtCalib = JSONArray(grav.toList())
            attempt = 0
            runCalib()
        }
    }

    private fun baseParams(): JSONObject {
        val p = JSONObject(settings.toParams().toString())
        intrinsics?.let { p.put("intrinsics", it) }
        gravityAtCalib?.let { p.put("gravity", it) }
        return p
    }

    private fun runCalib() {
        val y = calibY ?: return
        setBusy(true, "관 테두리 찾는 중… (처음 실행 시 몇 초 더 걸림)")
        val params = baseParams().put("attempt", attempt)
        hint?.let { params.put("hint", JSONArray(listOf(it[0], it[1]))) }
        val f = File(cacheDir, "monitor_calib.y")
        worker.execute {
            val r = try {
                f.writeBytes(y)
                PyBridge.monitorCalibrate(this, f, calibW, calibH, calibRot, params)
            } catch (e: Throwable) { JSONObject().put("ok", false).put("error", e.toString()) }
            val bmp = runCatching { yToBitmap(y, calibW, calibH, calibRot) }.getOrNull()
            runOnUiThread { calib = r; showCalib(r, bmp) }
        }
    }

    private fun showCalib(r: JSONObject, bmp: Bitmap?) {
        stage = Stage.CALIB
        boxSetup.visibility = View.GONE; boxCalib.visibility = View.VISIBLE
        imgCalib.visibility = View.VISIBLE
        bmp?.let { imgCalib.setImageBitmap(it) }
        setBusy(false, "초록: 관 내경 · 빨강: 지금 수면선")
        if (r.optBoolean("ok")) {
            r.optJSONObject("ellipse")?.let { e ->
                imgCalib.ellipse = OverlayImageView.Ell(e.getDouble("cx"), e.getDouble("cy"), e.getDouble("a"), e.getDouble("b"), e.getDouble("phi_deg"))
            }
            imgCalib.waterline = r.optJSONArray("waterline")?.let { w ->
                doubleArrayOf(w.getJSONArray(0).getDouble(0), w.getJSONArray(0).getDouble(1), w.getJSONArray(1).getDouble(0), w.getJSONArray(1).getDouble(1))
            }
            val conf = if (r.isNull("confidence")) "-" else String.format(Locale.US, "%.2f", r.optDouble("confidence"))
            txtCalib.text = String.format(Locale.US,
                "지금 수심 %.1f mm (충만율 %.0f%%) · 수면선 신뢰도 %s\n" +
                    "초록 타원이 관 내경과 맞으면 시작하세요. 기록하는 동안 이 테두리를 그대로 씁니다 — 시작한 뒤에는 폰을 움직이지 마세요.\n" +
                    "맞지 않으면 '다시 찾기', 그래도 안 되면 관 안쪽을 탭한 뒤 '다시 찍기'.",
                r.optDouble("depth_mm"), r.optDouble("fill_ratio") * 100, conf)
        } else {
            imgCalib.ellipse = null; imgCalib.waterline = null
            txtCalib.text = "관 테두리를 찾지 못했습니다.\n${r.optString("error")}\n관 끝단이 화면 가운데 크게 보이게 하고 관 안쪽을 탭한 뒤 '다시 찍기'."
        }
        btnBegin.isEnabled = r.optBoolean("ok")
    }

    // ------------------------------------------------------------------ 시작
    private fun begin() {
        val c = calib ?: return
        if (!c.optBoolean("ok")) return
        val now = System.currentTimeMillis()
        if (endMs <= now) { Toast.makeText(this, "끝 시각이 이미 지났습니다. 기간을 다시 정하세요", Toast.LENGTH_LONG).show(); toSetup(); return }
        if (endMs <= startMs) { Toast.makeText(this, "끝 시각이 시작보다 늦어야 합니다", Toast.LENGTH_LONG).show(); toSetup(); return }
        val place = editPlace.text.toString().trim(); val pipe = editPipe.text.toString().trim()
        intervalMin = INTERVALS[spinInterval.selectedItemPosition]
        torchOnRecord = chkTorch.isChecked
        val d = SessionStore.newMonitor(this, place, pipe)
        File(d, "calib.y").writeBytes(calibY!!)
        val m = JSONObject()
            .put("type", "monitor").put("schema", 1)
            .put("app_version", BuildConfig.VERSION_NAME).put("app_build", BuildConfig.GIT_SHA)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
            .put("created_ms", now).put("start_ms", startMs).put("end_ms", endMs).put("interval_min", intervalMin)
            .put("place", place).put("pipe_id", pipe).put("torch_on_record", torchOnRecord)
            .put("params", settings.toParams())
            .put("image", JSONObject().put("width", calibW).put("height", calibH).put("rotation", calibRot).put("calib_file", "calib.y"))
            .put("zoom_ratio", zoom.toDouble())
            .put("avg_frames", AVG_FRAMES).put("thumb_max_side", THUMB)
            .put("ellipse", c.getJSONObject("ellipse"))
            .put("calibration", JSONObject().put("depth_mm", c.optDouble("depth_mm")).put("fill_ratio", c.optDouble("fill_ratio"))
                .put("confidence", c.opt("confidence") ?: JSONObject.NULL).put("inner_source", c.opt("inner_source") ?: JSONObject.NULL)
                .put("waterline", c.opt("waterline") ?: JSONObject.NULL).put("attempt", attempt))
            .put("status", "running")
        intrinsics?.let { m.put("intrinsics", it) }
        gravityAtCalib?.let { m.put("gravity", it) }
        locTracker.toJson()?.let { m.put("location", it) }
        File(d, SessionStore.MONITOR).writeText(m.toString(1))
        MonitorLog.ensureHeader(d)
        File(d, "img").mkdirs()
        SessionStore.setLastValue(this, "place", place)
        SessionStore.setLastValue(this, "pipe_id", pipe)
        dir = d; monitor = m
        lastSlot = -1; nOk = 0; nFail = 0
        enterRun()
    }

    /** 앱이 꺼졌다 다시 켜졌을 때 이어서 */
    private fun resume(d: File) {
        val m = SessionStore.readJson(File(d, SessionStore.MONITOR)) ?: return
        dir = d; monitor = m
        startMs = m.optLong("start_ms"); endMs = m.optLong("end_ms"); intervalMin = m.optInt("interval_min", 5)
        torchOnRecord = m.optBoolean("torch_on_record"); zoom = m.optDouble("zoom_ratio", 1.0).toFloat()
        intrinsics = m.optJSONObject("intrinsics"); gravityAtCalib = m.optJSONArray("gravity")
        val rows = MonitorLog.read(d)
        nOk = rows.count { it["ok"] == "1" }; nFail = rows.size - nOk
        val iv = intervalMin * 60_000L
        lastSlot = rows.lastOrNull()?.get("epoch_ms")?.toLongOrNull()?.let { (it - startMs) / iv } ?: -1L
        enterRun()
    }

    private fun enterRun() {
        stage = Stage.RUN
        boxRun.visibility = View.VISIBLE
        val m = monitor ?: return
        txtRunTitle.text = "정기 기록 중 · ${m.optString("place")} / ${m.optString("pipe_id")}"
        chart.tStart = startMs; chart.tEnd = endMs
        refreshChart()
        setBright(false)
        scheduleNext()
    }

    // ------------------------------------------------------------------ 기록 시각
    private fun ivMs() = intervalMin * 60_000L

    private fun scheduleNext() {
        handler.removeCallbacks(tick)
        val d = dir ?: return
        val now = System.currentTimeMillis()
        if (now > endMs) { finishRun("finished"); return }
        val iv = ivMs()
        val k = if (now <= startMs) 0L else (now - startMs + iv - 1) / iv
        // 지나간 시각인데 기록하지 못한 것 → '기록 못 함' 줄 (그래프에서 빈 구간)
        var s = lastSlot + 1
        while (s < k) {
            val t = startMs + s * iv
            if (t > endMs) break
            MonitorLog.append(d, t, mapOf("ok" to false, "note" to "기록 못 함 (앱이 화면에 없었거나 이전 계산 중)"))
            nFail++
            s++
        }
        if (k - 1 > lastSlot) lastSlot = k - 1
        nextAt = startMs + k * iv
        if (nextAt > endMs) { finishRun("finished"); return }
        val lead = if (torchOnRecord) TORCH_WARMUP_MS else 0L
        handler.postDelayed(tick, max(0L, nextAt - lead - now))
        updateRunText()
    }

    private fun onTick() {
        val d = dir ?: return
        val t = nextAt
        val k = (t - startMs) / ivMs()
        val cam = camera
        if (cam == null && !busy && System.currentTimeMillis() - t < 30_000L) {   // 카메라가 켜지는 중이면 잠시 뒤 다시
            handler.postDelayed(tick, 2000L)
            return
        }
        if (busy || cam == null) {
            val why = if (busy) "이전 기록 계산 중" else "카메라 준비 안 됨"
            MonitorLog.append(d, t, mapOf("ok" to false, "note" to why))
            nFail++; lastSlot = k
            handler.postDelayed({ scheduleNext() }, 1000L)
            return
        }
        busy = true
        val torch = torchOnRecord && cam.cameraInfo.hasFlashUnit()
        if (torch) cam.cameraControl.enableTorch(true)
        val start = Runnable {
            grab = Grab(AVG_FRAMES, 100L) { fs, w, h, rot ->
                grabTimeout?.let { handler.removeCallbacks(it) }
                if (torch) camera?.cameraControl?.enableTorch(false)
                record(t, k, fs, w, h, rot, torch)
            }
            val to = Runnable {
                if (busy && grab != null) {
                    grab = null
                    if (torch) camera?.cameraControl?.enableTorch(false)
                    MonitorLog.append(d, t, mapOf("ok" to false, "note" to "사진을 받지 못함 (카메라 응답 없음)"))
                    nFail++; lastSlot = k; busy = false
                    scheduleNext()
                }
            }
            grabTimeout = to
            handler.postDelayed(to, GRAB_TIMEOUT_MS)
        }
        if (torch) handler.postDelayed(start, max(0L, t - System.currentTimeMillis())) else start.run()
    }

    private fun record(t: Long, k: Long, fs: List<ByteArray>, w: Int, h: Int, rot: Int, torch: Boolean) {
        val d = dir ?: return
        val m = monitor ?: return
        val img = m.optJSONObject("image")
        val (bat, charging) = battery()
        worker.execute {
            val v = HashMap<String, Any?>()
            v["torch"] = torch; v["battery_pct"] = bat; v["charging"] = charging
            try {
                val avg = average(fs)
                if (img != null && (img.optInt("width") != w || img.optInt("height") != h || img.optInt("rotation") != rot)) {
                    v["ok"] = false; v["note"] = "영상 크기·방향이 기준과 다름 (${w}x$h, ${rot}°)"
                } else {
                    val tmp = File(cacheDir, "monitor_rec.y"); tmp.writeBytes(avg)
                    val params = JSONObject(m.optJSONObject("params")?.toString() ?: "{}")
                        .put("ellipse", m.getJSONObject("ellipse"))
                    m.optJSONObject("intrinsics")?.let { params.put("intrinsics", it) }
                    m.optJSONArray("gravity")?.let { params.put("gravity", it) }
                    val r = PyBridge.monitorMeasure(this, listOf(tmp), w, h, rot, params)
                    v["ok"] = r.optBoolean("ok")
                    if (r.optBoolean("ok")) {
                        v["depth_mm"] = r.optDouble("depth_mm"); v["fill_pct"] = r.optDouble("fill_ratio") * 100
                        v["confidence"] = if (r.isNull("confidence")) null else r.optDouble("confidence")
                        v["roll_deg"] = r.optDouble("roll_deg"); v["brightness"] = r.optDouble("brightness")
                        r.optJSONArray("waterline")?.let { wl ->
                            v["wl_x1"] = wl.getJSONArray(0).getDouble(0); v["wl_y1"] = wl.getJSONArray(0).getDouble(1)
                            v["wl_x2"] = wl.getJSONArray(1).getDouble(0); v["wl_y2"] = wl.getJSONArray(1).getDouble(1)
                        }
                        val a = r.optJSONArray("warnings")
                        if (a != null && a.length() > 0) {
                            v["note"] = (0 until a.length()).joinToString(" / ") { a.getString(it) }
                        }
                    } else {
                        v["note"] = r.optString("error")
                    }
                    // 작은 사진 (나중에 수면선 확인·학습용)
                    runCatching {
                        val name = "img/" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(t)) + ".jpg"
                        val bmp = yToBitmap(avg, w, h, rot, THUMB)
                        FileOutputStream(File(d, name)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                        v["image"] = name
                    }
                }
            } catch (e: Throwable) {
                v["ok"] = false; v["note"] = e.toString()
            }
            MonitorLog.append(d, t, v)
            runOnUiThread {
                if (v["ok"] == true) {
                    nOk++
                    lastResult = String.format(Locale.US, "%s  수심 %.1f mm", SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(t)), v["depth_mm"] as Double)
                } else {
                    nFail++
                    lastResult = "${SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(t))}  실패: ${v["note"]}"
                }
                lastSlot = k; busy = false
                refreshChart()
                if (stage == Stage.RUN && !isFinishing) scheduleNext()
            }
        }
    }

    private fun battery(): Pair<Int, Boolean> {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1 to false
        val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return (if (lvl >= 0 && sc > 0) lvl * 100 / sc else -1) to plugged
    }

    private fun refreshChart() {
        val d = dir ?: return
        chart.points = MonitorLog.points(MonitorLog.read(d))
        updateRunText()
    }

    private fun updateRunText() {
        val (bat, charging) = battery()
        val now = System.currentTimeMillis()
        val next = when {
            busy -> "기록 중…"
            nextAt > now -> "다음 기록 ${SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(nextAt))}"
            else -> "-"
        }
        txtRun.text = String.format(Locale.US,
            "기간 %s ~ %s · %d분마다\n%s\n기록 %d건 (실패·놓침 %d건)\n마지막: %s\n배터리 %s%%%s",
            tf.format(Date(startMs)), tf.format(Date(endMs)), intervalMin, next, nOk, nFail, lastResult,
            if (bat >= 0) bat.toString() else "-", if (charging) " · 충전 중" else " · ⚠ 충전기를 연결하세요")
    }

    private fun setBright(on: Boolean) {
        val lp = window.attributes
        lp.screenBrightness = if (on || stage != Stage.RUN) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else 0.02f
        window.attributes = lp
    }

    // ------------------------------------------------------------------ 끝
    private fun confirmStop() {
        setBright(true)
        AlertDialog.Builder(this)
            .setTitle("정기 기록 중지")
            .setMessage("지금까지의 기록은 저장됩니다. 중지할까요?")
            .setPositiveButton("중지") { _, _ -> finishRun("stopped") }
            .setNegativeButton("계속", null).show()
    }

    private fun finishRun(status: String) {
        handler.removeCallbacks(tick)
        grab = null
        val d = dir ?: return
        val m = SessionStore.readJson(File(d, SessionStore.MONITOR)) ?: monitor ?: JSONObject()
        if (m.optString("status") == "running") {
            m.put("status", status).put("ended_ms", System.currentTimeMillis())
            File(d, SessionStore.MONITOR).writeText(m.toString(1))
        }
        stage = Stage.SETUP
        setBright(true)
        Toast.makeText(this, if (status == "finished") "정기 기록을 마쳤습니다" else "정기 기록을 중지했습니다", Toast.LENGTH_LONG).show()
        startActivity(Intent(this, MonitorViewActivity::class.java).putExtra(MonitorViewActivity.EXTRA_DIR, d.absolutePath))
        finish()
    }
}
