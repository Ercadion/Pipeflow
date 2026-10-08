package io.github.ercadion.pipeflow

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.io.File

/** Python 쪽에서 listener.onProgress(msg) 로 호출 */
class ProgressListener(private val cb: (String) -> Unit) {
    fun onProgress(msg: String) = cb(msg)
}

object PyBridge {
    fun ensureStarted(ctx: Context) {
        if (!Python.isStarted()) Python.start(AndroidPlatform(ctx.applicationContext))
    }

    /** pipeflow_app.analyze 호출 (반드시 백그라운드 스레드에서) */
    fun analyze(ctx: Context, dir: File, params: JSONObject, onProgress: (String) -> Unit): JSONObject {
        ensureStarted(ctx)
        val mod = Python.getInstance().getModule("pipeflow_app")
        val res = mod.callAttr("analyze", dir.absolutePath, params.toString(), ProgressListener(onProgress))
        return JSONObject(res.toString())
    }

    /** pipeflow_export.export_json — 측정 폴더들 → 엑셀(.xlsx) (+ csvDir 가 있으면 시트별 CSV). 백그라운드 스레드에서 */
    fun exportXlsx(ctx: Context, dirs: List<File>, out: File, csvDir: File? = null): JSONObject {
        ensureStarted(ctx)
        val mod = Python.getInstance().getModule("pipeflow_export")
        val paths = org.json.JSONArray(dirs.map { it.absolutePath }).toString()
        val tz = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000
        val res = mod.callAttr("export_json", paths, out.absolutePath,
            "PipeFlow 앱 v${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})", csvDir?.absolutePath ?: "", tz)
        return JSONObject(res.toString())
    }
}
