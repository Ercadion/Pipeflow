package io.github.ercadion.pipeflow

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import kotlin.math.atan2

/**
 * 촬영: CameraX ImageAnalysis 로 Y(밝기) 평면을 직접 받아 저장.
 *  - 첫 프레임: 원 해상도 still.y (수위 계산용)
 *  - 모든 프레임: 2×2 평균 축소 frames.y + 실제 타임스탬프 (H-STIV 용)
 *  - 중력 센서 평균 (영상 속 수평 방향 → 수면선 탐색 범위 제한)
 */
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
class CaptureActivity : AppCompatActivity(), SensorEventListener {
    private lateinit var previewView: PreviewView
    private lateinit var guide: GuideOverlayView
    private lateinit var txtStatus: TextView
    private lateinit var btnRecord: Button
    private lateinit var executor: ExecutorService
    private lateinit var settings: Settings
    private var camera: Camera? = null
    private var provider: ProcessCameraProvider? = null
    private var fpsRange: Range<Int>? = null

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

    // 중력
    private lateinit var sensorManager: SensorManager
    private val grav = FloatArray(3)
    private val gravSum = DoubleArray(3)
    private var gravN = 0

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
        settings = Settings.load(this)
        executor = Executors.newSingleThreadExecutor()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        btnRecord.setOnClickListener { startRecording() }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            startCamera() else permLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        val s = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        s?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }

    override fun onSensorChanged(e: SensorEvent) {
        grav[0] = e.values[0]; grav[1] = e.values[1]; grav[2] = e.values[2]
        if (recording) { for (i in 0..2) gravSum[i] += grav[i].toDouble(); gravN++ }
        // 영상 속 '아래' 방향 (세로 화면, 후면 카메라): (-gx, gy)
        val roll = Math.toDegrees(atan2(-grav[0].toDouble(), grav[1].toDouble())).toFloat()
        guide.rollDeg = roll
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

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
                camera = prov.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                Toast.makeText(this, "카메라 시작 실패: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startRecording() {
        if (recording) return
        val dir = SessionStore.newSession(this)
        sessionDir = dir
        framesOut = BufferedOutputStream(FileOutputStream(File(dir, "frames.y")), 1 shl 20)
        timestamps.clear(); stillW = 0
        for (i in 0..2) gravSum[i] = 0.0
        gravN = 0; startNs = 0L
        recording = true
        guide.recording = true
        btnRecord.isEnabled = false
        btnRecord.text = "촬영 중…"
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
            if (!recording) {
                if ((ts / 1_000_000) % 10 < 2) runOnUiThread { updateStatus(image.width, image.height) }
                return
            }
            val w = image.width; val h = image.height
            val plane = image.planes[0]
            val buf = plane.buffer
            val rs = plane.rowStride; val ps = plane.pixelStride
            if (yBuf.size != w * h) yBuf = ByteArray(w * h)
            // 행 단위 복사 (rowStride 패딩 제거)
            buf.rewind()
            if (ps == 1) {
                for (y in 0 until h) {
                    buf.position(y * rs)
                    buf.get(yBuf, y * w, w)
                }
            } else {
                for (y in 0 until h) for (x in 0 until w) yBuf[y * w + x] = buf.get(y * rs + x * ps)
            }
            if (stillW == 0) {
                stillW = w; stillH = h; rotation = image.imageInfo.rotationDegrees
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
            runOnUiThread { Toast.makeText(this, "프레임 저장 오류: ${e.message}", Toast.LENGTH_LONG).show() }
        } finally {
            image.close()
        }
    }

    private fun updateStatus(w: Int, h: Int) {
        txtStatus.text = String.format(Locale.US,
            "분석 해상도 %d×%d · 실제 %.0f fps (목표 %s) · 촬영 %.0f초\n관 단면을 원 안에 맞추고, 수면을 위에서 비스듬히 내려다보세요",
            w, h, fpsEstimate, fpsRange?.toString() ?: "기본", settings.durationSec)
    }

    private fun finishRecording() {
        recording = false
        framesOut?.flush(); framesOut?.close(); framesOut = null
        val dir = sessionDir ?: return
        val meta = JSONObject()
        meta.put("app_version", BuildConfig.VERSION_NAME)
        meta.put("device", "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
        meta.put("rotation_degrees", rotation)
        meta.put("still", JSONObject().put("file", "still.y").put("width", stillW).put("height", stillH))
        meta.put("frames", JSONObject().put("file", "frames.y").put("width", frameW).put("height", frameH)
            .put("count", timestamps.size).put("timestamps_ns", JSONArray(timestamps))
            .put("fps", fpsEstimate).put("binning", 2))
        meta.put("fps_range", fpsRange?.toString())
        if (gravN > 0) meta.put("gravity", JSONArray(gravSum.map { it / gravN }))
        meta.put("params", settings.toParams())
        // 카메라 내부 파라미터 (still 해상도 기준)
        try {
            val cam = camera
            val ch = if (cam != null) {
                val info = Camera2CameraInfo.from(cam.cameraInfo)
                (getSystemService(Context.CAMERA_SERVICE) as CameraManager).getCameraCharacteristics(info.cameraId)
            } else backCameraCharacteristics()
            if (ch != null) meta.put("intrinsics", CameraIntrinsics.compute(ch, stillW, stillH))
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
