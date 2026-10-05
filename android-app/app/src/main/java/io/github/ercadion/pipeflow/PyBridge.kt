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
}
