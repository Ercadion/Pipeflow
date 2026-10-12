package io.github.ercadion.pipeflow

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.util.Locale

/** 설정 탭: 앱 버전, 측정 데이터 용량·위치, 임시 파일 정리, 권한 상태, 측정 가이드 */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        title = "설정"
        NavBar.setup(this, R.id.nav_settings)
        findViewById<TextView>(R.id.txtVersion).text = "v${BuildConfig.VERSION_NAME} · 빌드 ${BuildConfig.GIT_SHA}"
        findViewById<Button>(R.id.btnClearCache).setOnClickListener {
            listOf("exports", "thumbs").forEach { File(cacheDir, it).deleteRecursively() }
            Toast.makeText(this, "임시 파일을 정리했습니다 (측정 데이터는 그대로)", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnAppSettings).setOnClickListener {
            startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    override fun onResume() {
        super.onResume()
        val all = SessionStore.list(this)
        val mon = all.count { SessionStore.isMonitor(it) }
        val bytes = SessionStore.sizeOf(SessionStore.root(this))
        findViewById<TextView>(R.id.txtStorage).text = String.format(Locale.US,
            "측정 %d건 · 정기 기록 %d건 · %.1f MB\n%s", all.size - mon, mon, bytes / 1e6, SessionStore.root(this).absolutePath)
        fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
        val cam = if (granted(Manifest.permission.CAMERA)) "허용" else "허용 안 됨 (측정 불가)"
        val loc = when {
            granted(Manifest.permission.ACCESS_FINE_LOCATION) -> "허용 (정확한 위치)"
            granted(Manifest.permission.ACCESS_COARSE_LOCATION) -> "허용 (대략적인 위치)"
            else -> "허용 안 됨 (위도·경도 빈칸)"
        }
        findViewById<TextView>(R.id.txtPerm).text = "카메라: $cam\n위치: $loc"
    }
}
