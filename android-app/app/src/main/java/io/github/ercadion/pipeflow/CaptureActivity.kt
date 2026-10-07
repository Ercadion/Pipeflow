package io.github.ercadion.pipeflow

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Bundle
import android.util.Range
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 촬영 화면
 *  - 미리보기 중: 저해상도(긴 변 ~320px)로 초당 ~8회 관 테두리 실시간 검출(LiveRimDetector)
 *    → 바깥 테두리(흰색) + 관 내경(빨강) 타원을 화면에 표시, 촬영 준비 체크리스트 갱신
 *  - 조명 버튼: 후면 플래시(토치) 켜기/끄기 (촬영 중에도 유지)
 *  - 두 손가락: 확대/축소 (두 번 탭: 1×), 탭: 그 위치에 초점·노출 + 그 주변을 관 검사 범위로 지정, 길게 누름: 검사 범위 해제
 *    (확대 배율은 meta.json 의 zoom_ratio 와 초점거리(f_px)에 반영)
 *  - 촬영: Y 평면 저장(still.y 원 해상도 + frames.y 2×2 축소) + 타임스탬프 + 중력 + 포착한 타원(live_ellipse)
 */
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
class CaptureActivity : AppCompatActivity(), SensorEventListener {
    private lateinit var previewView: PreviewView
    private lateinit var guide: GuideOverlayView
    private lateinit var txtStatus: TextView
    private lateinit var btnRecord: Button
    private lateinit var btnTorch: Button
    private lateinit var executor: ExecutorService
    private lateinit var settings: Settings
    private var camera: Camera? = null
    private var provider: ProcessCameraProvider? = null
    private var fpsRange: Range<Int>? = null
    private var torchOn = false

    // 확대/축소, 터치 초점·검사 범위
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector
    private var zoomReq = 1f
    /** 검사 범위 (분석 영상 = 센서 방향 원 해상도 좌표) [x, y, r], null = 없음 */
    @Volatile private var roi: DoubleArray? = null
    @Volatile private var roiVersion = 0
    private var roiApplied = -1
    // 화면 ↔ 영상 좌표 변환용 (updateOverlay 에서 갱신, UI 스레드)
    private var mapW = 0; private var mapH = 0; private var mapRot = 0
    private var mapSc = 1.0; private var mapOx = 0.0; private var mapOy = 0.0

    // 녹화 상태 (analyzer 스레드에서 사용)
    @Volatile private var recording = false
    private var sessionDir: File? = null
    private var framesOut: BufferedOutputStream? = null
    private val timestamps = ArrayList<Long>()
    private var stillW = 0; private var stillH = 0; private var frameW = 0; private var frameH = 0
    private var rotation = 0
    private var startNs = 0L
    private var lastTs = 0L
    private var fpsEstimate = 0.0
    private var yBuf = ByteArray(0)
    private var halfBuf = ByteArray(0)
    private var smallBuf = ByteArray(0)

    // 실시간 테두리 검출 (analyzer 스레드)
    // 검출 + 프레임 간 안정화(히스테리시스): 비슷한 타원이 연속으로 나와야 표시/교체 → 널뛰기 방지
    private val tracker = LiveRimTracker()
    private var lastDetectNs = 0L
    @Volatile private var smooth: LiveRimDetector.Ellipse? = null   // 센서 방향 원 해상도 좌표 (표시용, 안정화됨)
    @Volatile private var locked = false
    private var lastCoverage = 0.0
    private var lastInnerSource = 0
    private var lastBrightness = 0.0
    private var lastSharpness = 0.0
    private var lastDetectMs = 0.0
    private var liveEllipseAtStart: LiveRimDetector.Ellipse? = null
    private var zoomAtStart = 1f
    private var roiAtStart: DoubleArray? = null
    private var liveCoverageAtStart = 0.0

    // 센서
    private lateinit var sensorManager: SensorManager
    private val grav = floatArrayOf(0f, 9.8f, 0f)
    private val gravSum = DoubleArray(3)
    private var gravN = 0
    @Volatile private var gyroMag = 0.0

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else { Toast.makeText(this, "카메라 권한이 필요합니다", Toast.LENGTH_LONG).show(); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture)
        previewView = findViewById(R.id.previewView)
        guide = findViewById(R.id.guide)
        txtStatus = findViewById(R.id.txtStatus)
        btnRecord = findViewById(R.id.btnRecord)
        btnTorch = findViewById(R.id.btnTorch)
        settings = Settings.load(this)
        executor = Executors.newSingleThreadExecutor()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        btnRecord.setOnClickListener { startRecording() }
        btnTorch.setOnClickListener { setTorch(!torchOn) }
        setupGestures()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            startCamera() else permLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        val g = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        g?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }

    // ------------------------------------------------------------------ 센서
    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type == Sensor.TYPE_GYROSCOPE) {
            val m = sqrt((e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble())
            gyroMag = 0.8 * gyroMag + 0.2 * m
            return
        }
        grav[0] = e.values[0]; grav[1] = e.values[1]; grav[2] = e.values[2]
        if (recording) { for (i in 0..2) gravSum[i] += grav[i].toDouble(); gravN++ }
        // 영상 속 '아래' 방향 (세로 화면, 후면 카메라): (-gx, gy)
        guide.rollDeg = Math.toDegrees(atan2(-grav[0].toDouble(), grav[1].toDouble())).toFloat()
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    /** 후면 카메라가 수평 아래로 내려다보는 각도 [°] (세로 화면 기준) */
    private fun lookDownDeg(): Double {
        val n = sqrt((grav[0] * grav[0] + grav[1] * grav[1] + grav[2] * grav[2]).toDouble()).coerceAtLeast(1e-6)
        return Math.toDegrees(asin((grav[2] / n).coerceIn(-1.0, 1.0)))
    }

    // ------------------------------------------------------------------ 카메라
    private fun backCameraCharacteristics(): CameraCharacteristics? {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return null
        return cm.getCameraCharacteristics(id)
    }

    private fun chooseFps(ch: CameraCharacteristics?, target: Int): Range<Int>? {
        val ranges = ch?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        return ranges.filter { it.lower == it.upper && it.upper <= target }.maxByOrNull { it.upper }
            ?: ranges.filter { it.upper <= target }.maxByOrNull { it.upper * 1000 + it.lower }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val prov = future.get(); provider = prov
            fpsRange = chooseFps(backCameraCharacteristics(), settings.fps)
            val res = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy(Size(1280, 960),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
            val pb = Preview.Builder().setResolutionSelector(
                ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build())
            val ab = ImageAnalysis.Builder()
                .setResolutionSelector(res)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_BLOCK_PRODUCER)
                .setImageQueueDepth(6)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            fpsRange?.let {
                Camera2Interop.Extender(pb).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
                Camera2Interop.Extender(ab).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
            }
            val preview = pb.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ab.build().also { it.setAnalyzer(executor, ::analyze) }
            try {
                prov.unbindAll()
                val cam = prov.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                camera = cam
                zoomReq = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                guide.zoomText = String.format(Locale.US, "%.1f×", zoomReq)
                val hasFlash = cam.cameraInfo.hasFlashUnit()
                btnTorch.visibility = if (hasFlash) View.VISIBLE else View.GONE
                if (torchOn && hasFlash) cam.cameraControl.enableTorch(true)
            } catch (e: Exception) {
                Toast.makeText(this, "카메라 시작 실패: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setTorch(on: Boolean) {
        val cam = camera ?: return
        if (!cam.cameraInfo.hasFlashUnit()) return
        cam.cameraControl.enableTorch(on)
        torchOn = on
        btnTorch.text = if (on) "조명 끄기" else "조명 켜기"
    }

    // ------------------------------------------------------------------ 확대/축소 · 터치 초점 · 검사 범위
    private fun setupGestures() {
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                setZoom(zoomReq * d.scaleFactor)
                return true
            }
        })
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { tapFocus(e.x, e.y); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean { setZoom(1f); return true }
            override fun onLongPress(e: MotionEvent) { clearRoi(true) }
        })
        guide.setOnTouchListener { v, ev ->
            if (recording) return@setOnTouchListener true      // 촬영 중에는 배율·초점 고정
            scaleDetector.onTouchEvent(ev)
            if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP) v.performClick()
            true
        }
    }

    private fun setZoom(z: Float) {
        val cam = camera ?: return
        val zs = cam.cameraInfo.zoomState.value ?: return
        val nz = z.coerceIn(zs.minZoomRatio, zs.maxZoomRatio)
        if (abs(nz - zoomReq) < 1e-3) return
        val k = nz / zoomReq
        zoomReq = nz
        cam.cameraControl.setZoomRatio(nz)
        guide.zoomText = String.format(Locale.US, "%.1f×", nz)
        // 검사 범위를 영상 중심 기준으로 같은 배율만큼 옮김
        val r = roi
        if (r != null && mapW > 0) {
            val cx = mapW / 2.0; val cy = mapH / 2.0
            val nr = doubleArrayOf(cx + (r[0] - cx) * k, cy + (r[1] - cy) * k, min(r[2] * k, 0.6 * min(mapW, mapH)))
            if (nr[0] in 0.0..mapW.toDouble() && nr[1] in 0.0..mapH.toDouble()) setRoi(nr) else clearRoi(false)
        } else {
            roiVersion += 1   // 배율이 바뀌면 추적도 새로
        }
    }

    /** 탭: 그 위치에 초점·노출 맞춤 + 주변(짧은 변의 45%)을 관 검사 범위로 */
    private fun tapFocus(vx: Float, vy: Float) {
        val cam = camera ?: return
        val pt = previewView.meteringPointFactory.createPoint(vx, vy)
        val action = FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .disableAutoCancel().build()
        cam.cameraControl.startFocusAndMetering(action)
        val p = viewToSensor(vx.toDouble(), vy.toDouble()) ?: return
        setRoi(doubleArrayOf(p[0], p[1], 0.45 * min(mapW, mapH)))
        Toast.makeText(this, "초점 맞춤 · 터치한 주변에서 관을 찾습니다 (길게 누르면 해제)", Toast.LENGTH_SHORT).show()
    }

    private fun setRoi(r: DoubleArray) { roi = r; roiVersion++ }

    private fun clearRoi(cancelFocus: Boolean) {
        if (roi == null && !cancelFocus) return
        roi = null; roiVersion++
        guide.roi = null
        if (cancelFocus) {
            camera?.cameraControl?.cancelFocusAndMetering()
            Toast.makeText(this, "검사 범위 해제 · 자동 초점", Toast.LENGTH_SHORT).show()
        }
    }

    /** 화면(PreviewView) 좌표 → 분석 영상(센서 방향) 좌표 */
    private fun viewToSensor(vx: Double, vy: Double): DoubleArray? {
        if (mapW == 0) return null
        val ux = (vx - mapOx) / mapSc; val uy = (vy - mapOy) / mapSc
        val w = mapW; val h = mapH
        return when ((mapRot / 90) % 4) {
            1 -> doubleArrayOf(uy, h - 1 - ux)
            2 -> doubleArrayOf(w - 1 - ux, h - 1 - uy)
            3 -> doubleArrayOf(w - 1 - uy, ux)
            else -> doubleArrayOf(ux, uy)
        }
    }

    // ------------------------------------------------------------------ 녹화
    private fun startRecording() {
        if (recording) return
        val dir = SessionStore.newSession(this)
        sessionDir = dir
        framesOut = BufferedOutputStream(FileOutputStream(File(dir, "frames.y")), 1 shl 20)
        timestamps.clear(); stillW = 0
        for (i in 0..2) gravSum[i] = 0.0
        gravN = 0; startNs = 0L
        liveEllipseAtStart = if (locked) smooth else null
        zoomAtStart = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: zoomReq
        roiAtStart = roi
        liveCoverageAtStart = lastCoverage
        recording = true
        guide.state = GuideOverlayView.State.RECORDING
        btnRecord.isEnabled = false
        btnRecord.text = "촬영 중…"
    }

    /** Y 평면을 yBuf 로 복사 (rowStride 패딩 제거) */
    private fun copyY(image: ImageProxy) {
        val w = image.width; val h = image.height
        val plane = image.planes[0]
        val buf = plane.buffer
        val rs = plane.rowStride; val ps = plane.pixelStride
        if (yBuf.size != w * h) yBuf = ByteArray(w * h)
        buf.rewind()
        if (ps == 1) {
            for (y in 0 until h) {
                buf.position(y * rs)
                buf.get(yBuf, y * w, w)
            }
        } else {
            for (y in 0 until h) for (x in 0 until w) yBuf[y * w + x] = buf.get(y * rs + x * ps)
        }
    }

    /** analyzer 스레드 */
    private fun analyze(image: ImageProxy) {
        try {
            val ts = image.imageInfo.timestamp
            if (lastTs != 0L) {
                val inst = 1e9 / (ts - lastTs).coerceAtLeast(1)
                fpsEstimate = if (fpsEstimate == 0.0) inst else 0.9 * fpsEstimate + 0.1 * inst
            }
            lastTs = ts
            rotation = image.imageInfo.rotationDegrees
            if (!recording) {
                if (ts - lastDetectNs >= 120_000_000L) {      // 초당 ~8회
                    lastDetectNs = ts
                    copyY(image)
                    liveDetect(image.width, image.height)
                }
                return
            }
            copyY(image)
            val w = image.width; val h = image.height
            if (stillW == 0) {
                stillW = w; stillH = h
                File(sessionDir, "still.y").writeBytes(yBuf)
                startNs = ts
            }
            // 2×2 평균 축소
            val hw = w / 2; val hh = h / 2
            if (halfBuf.size != hw * hh) halfBuf = ByteArray(hw * hh)
            for (y in 0 until hh) {
                val r0 = (2 * y) * w; val r1 = r0 + w; val o = y * hw
                for (x in 0 until hw) {
                    val c = 2 * x
                    val s = (yBuf[r0 + c].toInt() and 0xFF) + (yBuf[r0 + c + 1].toInt() and 0xFF) +
                            (yBuf[r1 + c].toInt() and 0xFF) + (yBuf[r1 + c + 1].toInt() and 0xFF)
                    halfBuf[o + x] = ((s + 2) shr 2).toByte()
                }
            }
            frameW = hw; frameH = hh
            framesOut?.write(halfBuf)
            timestamps.add(ts)
            val elapsed = (ts - startNs) / 1e9
            runOnUiThread {
                txtStatus.text = String.format(Locale.US, "촬영 중 %.1f / %.0f 초 · %d 프레임 · %.0f fps",
                    elapsed, settings.durationSec, timestamps.size, fpsEstimate)
            }
            if (elapsed >= settings.durationSec) finishRecording()
        } catch (e: Exception) {
            runOnUiThread { Toast.makeText(this, "프레임 처리 오류: ${e.message}", Toast.LENGTH_LONG).show() }
        } finally {
            image.close()
        }
    }

    // ------------------------------------------------------------------ 실시간 검출
    private fun liveDetect(w: Int, h: Int) {
        val f = max(1, ceil(max(w, h) / 320.0).toInt())
        val sw = w / f; val sh = h / f
        if (smallBuf.size != sw * sh) smallBuf = ByteArray(sw * sh)
        val area = f * f
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                var s = 0
                val y0 = y * f; val x0 = x * f
                for (dy in 0 until f) {
                    val row = (y0 + dy) * w + x0
                    for (dx in 0 until f) s += yBuf[row + dx].toInt() and 0xFF
                }
                smallBuf[y * sw + x] = (s / area).toByte()
            }
        }
        val ver = roiVersion
        if (ver != roiApplied) {          // 검사 범위 변경(또는 배율 변경) → 추적 새로
            roiApplied = ver
            val rr = roi
            if (rr != null) tracker.setRoi(rr[0] / f, rr[1] / f, rr[2] / f) else tracker.clearRoi()
        }
        val r = tracker.update(smallBuf, sw, sh)
        lastDetectMs = r.millis
        if (r.found) { lastCoverage = r.coverage; lastBrightness = r.brightness; lastSharpness = r.sharpness; lastInnerSource = r.innerSource }
        smooth = tracker.current()?.scaled(f.toDouble())
        locked = smooth != null
        val sm = smooth
        val bright = if (r.found) r.brightness else meanBrightness(smallBuf)
        runOnUiThread { updateOverlay(sm, w, h, bright) }
    }

    private fun meanBrightness(b: ByteArray): Double {
        var s = 0L
        var i = 0
        while (i < b.size) { s += (b[i].toInt() and 0xFF); i += 4 }
        return s.toDouble() / max(1, b.size / 4)
    }

    /** 센서 방향 좌표 → 세로(업라이트) 영상 좌표 */
    private fun toUpright(x: Double, y: Double, w: Int, h: Int, rot: Int): DoubleArray = when ((rot / 90) % 4) {
        1 -> doubleArrayOf(h - 1 - y, x)
        2 -> doubleArrayOf(w - 1 - x, h - 1 - y)
        3 -> doubleArrayOf(y, w - 1 - x)
        else -> doubleArrayOf(x, y)
    }

    private fun updateOverlay(e: LiveRimDetector.Ellipse?, w: Int, h: Int, brightness: Double) {
        if (recording) return
        val rot = rotation
        val uw = if (rot % 180 == 0) w else h
        val uh = if (rot % 180 == 0) h else w
        // PreviewView FILL_CENTER 매핑
        val vw = previewView.width.toDouble(); val vh = previewView.height.toDouble()
        val sc = max(vw / uw, vh / uh)
        val ox = (vw - uw * sc) / 2; val oy = (vh - uh * sc) / 2
        mapW = w; mapH = h; mapRot = rot; mapSc = sc; mapOx = ox; mapOy = oy
        guide.roi = roi?.let { r ->
            val u = toUpright(r[0], r[1], w, h, rot)
            floatArrayOf((u[0] * sc + ox).toFloat(), (u[1] * sc + oy).toFloat(), (r[2] * sc).toFloat())
        }
        val checks = ArrayList<GuideOverlayView.Check>()
        var ready: Boolean

        if (e == null || !locked) {
            guide.state = GuideOverlayView.State.SEARCHING
            guide.outer = null; guide.inner = null; guide.centerView = null
            guide.headline = if (roi != null) "터치한 범위에서 관 테두리를 찾는 중…" else "관 테두리를 찾는 중…"
            checks += GuideOverlayView.Check("관 포착", 2,
                if (roi != null) "못 찾으면 관 위치를 다시 탭" else "관 끝단을 비추거나 관 위치를 탭하세요")
            ready = false
        } else {
            // e = 관 내경 테두리 (검출기가 끝단 면의 안쪽 동심 테두리를 골라 줌 → 관 두께 입력 불필요)
            val n = 72
            val inner = FloatArray(2 * n)
            var inside = true
            val minU = 0.01 * min(uw, uh)
            for (i in 0 until n) {
                val t = 2 * Math.PI * i / n
                val ui = toUpright(e.point(t)[0], e.point(t)[1], w, h, rot)
                if (ui[0] < minU || ui[0] > uw - minU || ui[1] < minU || ui[1] > uh - minU) inside = false
                inner[2 * i] = (ui[0] * sc + ox).toFloat(); inner[2 * i + 1] = (ui[1] * sc + oy).toFloat()
            }
            val uc = toUpright(e.cx, e.cy, w, h, rot)
            guide.outer = null; guide.inner = inner
            guide.centerView = floatArrayOf((uc[0] * sc + ox).toFloat(), (uc[1] * sc + oy).toFloat())
            guide.state = GuideOverlayView.State.LOCKED

            // 촬영 준비 체크
            val cov = lastCoverage
            checks += GuideOverlayView.Check("관 포착", if (cov >= 0.6) 0 else 1,
                String.format(Locale.US, "테두리 확인 %.0f%%", cov * 100))
            checks += GuideOverlayView.Check("내경", 0,
                if (lastInnerSource == 2) "끝단 면 안쪽 테두리" else if (lastInnerSource == 1) "안쪽 테두리 (바깥 테두리 확인)" else "테두리 하나 → 내경으로 사용")
            val fill = e.a / (min(uw, uh) / 2.0)
            checks += GuideOverlayView.Check("크기", when {
                fill < 0.45 -> 1; fill > 0.98 -> 1; else -> 0 },
                when { fill < 0.45 -> "조금 더 가까이"; fill > 0.98 -> "조금 더 멀리"; else -> String.format(Locale.US, "화면의 %.0f%%", fill * 100) })
            val offX = abs(uc[0] - uw / 2.0) / uw; val offY = abs(uc[1] - uh / 2.0) / uh
            checks += GuideOverlayView.Check("중앙", if (max(offX, offY) < 0.15) 0 else 1,
                if (max(offX, offY) < 0.15) "좋음" else "관을 화면 가운데로")
            checks += GuideOverlayView.Check("테두리 전체", if (inside) 0 else 2, if (inside) "화면 안" else "잘림 — 조금 멀리")
            val stable = gyroMag < 0.06
            checks += GuideOverlayView.Check("흔들림", if (stable) 0 else 1, if (stable) "안정" else "폰을 고정하세요")
            val tilt = Math.toDegrees(acos((e.b / e.a).coerceIn(0.0, 1.0)))
            checks += GuideOverlayView.Check("각도", if (e.b / e.a >= 0.2) 0 else 1,
                String.format(Locale.US, "관 기울기 %.0f° · 내려다봄 %.0f°", tilt, lookDownDeg()))
            ready = cov >= 0.6 && fill in 0.45..0.98 && inside && stable && max(offX, offY) < 0.15
            guide.headline = if (ready) "촬영 준비 완료" else "빨간 원이 관 내경에 맞도록 조정하세요"
        }
        val dark = brightness < 50
        checks += GuideOverlayView.Check("밝기", when { dark -> 2; brightness > 230 -> 1; else -> 0 },
            when { dark -> if (torchOn) "어두움" else "어두움 — 조명을 켜세요"; brightness > 230 -> "너무 밝음(반사)"; else -> "좋음" })
        if (dark) ready = false
        guide.checks = checks
        guide.ready = ready
        btnRecord.text = if (ready) "촬영 (준비 완료)" else "촬영 (준비 안 됨)"
        btnRecord.setBackgroundColor(if (ready) Color.rgb(46, 160, 67) else Color.rgb(120, 120, 120))
        txtStatus.text = String.format(Locale.US, "배율 %.1f× · 분석 %d×%d · %.0f fps (목표 %s) · 검출 %.0f ms · 촬영 %.0f초\n" +
            "두 손가락: 확대/축소 · 탭: 초점+검사 범위 · 길게: 해제 · 두 번 탭: 1×",
            zoomReq, w, h, fpsEstimate, fpsRange?.toString() ?: "기본", lastDetectMs, settings.durationSec)
    }

    // ------------------------------------------------------------------ 저장
    private fun finishRecording() {
        recording = false
        framesOut?.flush(); framesOut?.close(); framesOut = null
        val dir = sessionDir ?: return
        val meta = JSONObject()
        meta.put("app_version", BuildConfig.VERSION_NAME)      // build.gradle.kts 의 versionName
        meta.put("app_build", BuildConfig.GIT_SHA)              // 빌드한 커밋 (GitHub Actions 빌드면 커밋 해시 앞 7자리)
        meta.put("device", "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
        meta.put("rotation_degrees", rotation)
        meta.put("still", JSONObject().put("file", "still.y").put("width", stillW).put("height", stillH))
        meta.put("frames", JSONObject().put("file", "frames.y").put("width", frameW).put("height", frameH)
            .put("count", timestamps.size).put("timestamps_ns", JSONArray(timestamps))
            .put("fps", fpsEstimate).put("binning", 2))
        meta.put("fps_range", fpsRange?.toString())
        meta.put("torch", torchOn)
        meta.put("zoom_ratio", zoomAtStart.toDouble())
        roiAtStart?.let { meta.put("touch_roi", JSONArray(it.toList())) }
        if (gravN > 0) meta.put("gravity", JSONArray(gravSum.map { it / gravN }))
        meta.put("params", settings.toParams())
        // 실시간으로 포착한 테두리 → 회전 보정된 still 좌표로 저장 (분석 시 정밀화의 시작값)
        liveEllipseAtStart?.let { e ->
            val c = toUpright(e.cx, e.cy, stillW, stillH, rotation)
            var phi = e.phi + Math.toRadians(rotation.toDouble())
            while (phi > Math.PI / 2) phi -= Math.PI
            while (phi <= -Math.PI / 2) phi += Math.PI
            meta.put("live_ellipse", JSONObject().put("cx", c[0]).put("cy", c[1]).put("a", e.a).put("b", e.b)
                .put("phi_deg", Math.toDegrees(phi)).put("coverage", liveCoverageAtStart))
        }
        try {
            val cam = camera
            val ch = if (cam != null) {
                val info = Camera2CameraInfo.from(cam.cameraInfo)
                (getSystemService(Context.CAMERA_SERVICE) as CameraManager).getCameraCharacteristics(info.cameraId)
            } else backCameraCharacteristics()
            if (ch != null) meta.put("intrinsics", CameraIntrinsics.compute(ch, stillW, stillH, zoomAtStart.toDouble()))
        } catch (e: Exception) {
            meta.put("intrinsics_error", e.toString())
        }
        File(dir, "meta.json").writeText(meta.toString(1))
        runOnUiThread {
            provider?.unbindAll()
            startActivity(Intent(this, ResultActivity::class.java).putExtra(ResultActivity.EXTRA_DIR, dir.absolutePath))
            finish()
        }
    }
}
