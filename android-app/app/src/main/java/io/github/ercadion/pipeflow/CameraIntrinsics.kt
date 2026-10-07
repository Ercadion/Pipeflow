package io.github.ercadion.pipeflow

import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * 카메라 내부 파라미터를 Camera2 정보로 계산 (체스보드 캘리브레이션 없이).
 * 스트림(w×h, 센서 방향) 은 (보정 전) 활성 화소 영역의 가운데를 같은 종횡비로 잘라 축소한 것으로 가정.
 *  1순위: LENS_INTRINSIC_CALIBRATION (API 28+, 기기가 제공할 때)
 *  2순위: 초점거리(mm) / 센서 폭(mm) × 화소 배열 폭
 */
object CameraIntrinsics {
    /** zoom: CameraX 확대 배율 (영상 중심을 잘라 확대 → 초점거리 × zoom, 주점은 중심 기준으로 zoom 배) */
    fun compute(ch: CameraCharacteristics, w: Int, h: Int, zoom: Double = 1.0): JSONObject {
        val o = JSONObject()
        val active = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!
        val pre = ch.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE) ?: active
        val pixArr = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val phys = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()

        // 스트림에 해당하는 잘린 영역 (pre-correction 좌표)
        val aw = pre.width().toDouble(); val ah = pre.height().toDouble()
        val cropW: Double; val cropH: Double
        if (aw / ah > w.toDouble() / h) { cropH = ah; cropW = ah * w / h } else { cropW = aw; cropH = aw * h / w }
        val offX = (aw - cropW) / 2; val offY = (ah - cropH) / 2
        val s = w / cropW

        var fFocal: Double? = null
        if (focal != null && phys != null && pixArr != null && phys.width > 0) {
            fFocal = focal / phys.width * pixArr.width * s
        }
        var fx: Double? = null; var cx = w / 2.0; var cy = h / 2.0; var source = "none"
        if (Build.VERSION.SDK_INT >= 28) {
            val intr = ch.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
            if (intr != null && intr.size >= 4 && intr[0] > 0f) {
                val fxi = intr[0] * s
                // 초점거리 기반 값과 30% 이상 다르면 신뢰하지 않음
                if (fFocal == null || abs(fxi - fFocal) / fFocal < 0.3) {
                    fx = 0.5 * (intr[0] + intr[1]) * s
                    cx = (intr[2] - offX) * s
                    cy = (intr[3] - offY) * s
                    source = "LENS_INTRINSIC_CALIBRATION"
                }
            }
            ch.get(CameraCharacteristics.LENS_DISTORTION)?.let {
                o.put("lens_distortion_camera2", JSONArray(it.map { v -> v.toDouble() }))
            }
        }
        if (fx == null && fFocal != null) { fx = fFocal; source = "focal_length/sensor_size" }
        val z = if (zoom > 0) zoom else 1.0
        if (z != 1.0) {
            fx = fx?.times(z)
            cx = w / 2.0 + (cx - w / 2.0) * z
            cy = h / 2.0 + (cy - h / 2.0) * z
            o.put("zoom_note", "배율 반영: f_px·주점을 zoom 배 (광학 렌즈 전환 기기는 근사)")
        }
        o.put("zoom_ratio", z)
        if (fx != null) o.put("f_px", fx)
        o.put("cx", cx); o.put("cy", cy); o.put("source", source)
        o.put("width", w); o.put("height", h)
        focal?.let { o.put("focal_mm", it.toDouble()) }
        phys?.let { o.put("sensor_mm", JSONArray(listOf(it.width.toDouble(), it.height.toDouble()))) }
        return o
    }
}
