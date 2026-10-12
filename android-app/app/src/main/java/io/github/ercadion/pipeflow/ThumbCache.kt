package io.github.ercadion.pipeflow

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.widget.ImageView
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * 목록용 작은 사진 (cache/thumbs) — 측정: still.y 를 줄여서, 정기 기록: 마지막 사진
 * 처음 한 번만 만들고 다음부터는 저장된 jpg 를 읽음. 화면 스레드를 막지 않게 백그라운드에서
 */
object ThumbCache {
    private val exec = Executors.newSingleThreadExecutor()
    private const val SIDE = 160

    fun load(ctx: Context, dir: File, view: ImageView) {
        val key = SessionStore.id(dir)
        view.tag = key
        view.setImageDrawable(null)
        exec.execute {
            val bmp = runCatching { get(ctx, dir, key) }.getOrNull()
            view.post { if (view.tag == key && bmp != null) view.setImageBitmap(bmp) }
        }
    }

    private fun get(ctx: Context, dir: File, key: String): Bitmap? {
        val f = File(File(ctx.cacheDir, "thumbs").apply { mkdirs() }, SessionStore.safe(key) + "_" + Integer.toHexString(key.hashCode()) + ".jpg")
        if (f.exists()) return BitmapFactory.decodeFile(f.absolutePath)
        val bmp = if (SessionStore.isMonitor(dir)) fromMonitor(dir) else fromStill(dir)
        bmp?.let { b -> runCatching { FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.JPEG, 80, it) } } }
        return bmp
    }

    private fun fromMonitor(dir: File): Bitmap? {
        val img = File(dir, "img").listFiles()?.filter { it.name.endsWith(".jpg") }?.maxByOrNull { it.name } ?: return null
        val o = BitmapFactory.Options().apply { inSampleSize = 4 }
        return BitmapFactory.decodeFile(img.absolutePath, o)
    }

    /** still.y (Y 평면) 를 건너뛰며 읽어 축소 + 회전 */
    private fun fromStill(dir: File): Bitmap? {
        val meta = SessionStore.readJson(File(dir, "meta.json")) ?: return null
        val st = meta.optJSONObject("still") ?: return null
        val w = st.optInt("width"); val h = st.optInt("height")
        val f = File(dir, st.optString("file", "still.y"))
        if (w <= 0 || h <= 0 || !f.exists()) return null
        val y = f.readBytes()
        val step = maxOf(1, maxOf(w, h) / SIDE)
        val sw = w / step; val sh = h / step
        val px = IntArray(sw * sh)
        for (j in 0 until sh) for (i in 0 until sw) {
            val v = y[(j * step) * w + i * step].toInt() and 0xFF
            px[j * sw + i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val b = Bitmap.createBitmap(px, sw, sh, Bitmap.Config.ARGB_8888)
        val rot = meta.optInt("rotation_degrees", 0)
        if (rot == 0) return b
        val m = Matrix().apply { postRotate(rot.toFloat()) }
        return Bitmap.createBitmap(b, 0, 0, sw, sh, m, true)
    }
}
