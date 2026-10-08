package io.github.ercadion.pipeflow

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * 촬영 위치 (위도·경도) — 촬영 화면이 열려 있는 동안 GPS·네트워크 위치를 받아 두고, 촬영할 때 가장 좋은 최근 값을 씀
 *  - 위치 권한이 없거나 아직 못 잡았으면 null (맨홀 안 등). 오래된 위치(기본 2분 초과)는 쓰지 않음
 *  - Google Play 서비스 없이 안드로이드 기본 LocationManager 사용
 */
class LocationTracker(private val ctx: Context) : LocationListener {
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    @Volatile private var best: Location? = null
    private var running = false

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        val m = lm ?: return               // 위치 서비스 없는 기기
        if (running || !hasPermission()) return
        running = true
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            runCatching {
                if (m.isProviderEnabled(p)) {
                    m.getLastKnownLocation(p)?.let { consider(it) }       // 최근 값이면 바로 사용 (나이는 아래에서 확인)
                    m.requestLocationUpdates(p, 1000L, 0f, this, Looper.getMainLooper())
                }
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { lm?.removeUpdates(this) }
    }

    private fun ageMs(l: Location) = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000

    /** 더 최근이면서 너무 부정확하지 않거나, 같은 시기에 더 정확한 값을 채택 */
    private fun consider(l: Location) {
        val b = best
        if (b == null) { best = l; return }
        val newer = l.elapsedRealtimeNanos - b.elapsedRealtimeNanos
        val acc = { x: Location -> if (x.hasAccuracy()) x.accuracy else 9999f }
        best = when {
            newer > 30_000_000_000L -> l                                   // 30초 이상 새것
            newer < -30_000_000_000L -> b
            acc(l) <= acc(b) -> l
            else -> b
        }
    }

    override fun onLocationChanged(location: Location) = consider(location)
    @Deprecated("old API") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    /** meta.json 의 location (maxAgeMs 보다 오래된 값이면 null) */
    fun toJson(maxAgeMs: Long = 120_000L): JSONObject? {
        val l = best ?: return null
        val age = ageMs(l)
        if (age > maxAgeMs) return null
        return JSONObject()
            .put("lat", Math.round(l.latitude * 1e6) / 1e6)
            .put("lon", Math.round(l.longitude * 1e6) / 1e6)
            .put("accuracy_m", if (l.hasAccuracy()) l.accuracy.toDouble() else JSONObject.NULL)
            .put("provider", l.provider ?: "")
            .put("time_ms", l.time)
            .put("age_s", age / 1000.0)
    }
}
