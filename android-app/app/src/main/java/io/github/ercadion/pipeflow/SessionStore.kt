package io.github.ercadion.pipeflow

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 측정 세션 저장소: <앱 외부 저장소>/sessions/yyyyMMdd_HHmmss/
 *   meta.json, still.y, frames.y, result.json, labels.json
 * 모든 세션이 그대로 신경망 학습용 데이터셋이 됨.
 */
object SessionStore {
    fun root(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "sessions").apply { mkdirs() }

    fun newSession(ctx: Context): File {
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(root(ctx), name).apply { mkdirs() }
    }

    fun list(ctx: Context): List<File> =
        root(ctx).listFiles { f -> f.isDirectory && File(f, "meta.json").exists() }
            ?.sortedByDescending { it.name } ?: emptyList()

    fun readJson(f: File): JSONObject? =
        if (f.exists()) runCatching { JSONObject(f.readText()) }.getOrNull() else null

    fun writeLabels(dir: File, update: (JSONObject) -> Unit) {
        val f = File(dir, "labels.json")
        val o = readJson(f) ?: JSONObject()
        update(o)
        o.put("updated_ms", System.currentTimeMillis())
        f.writeText(o.toString(1))
    }

    /** 세션 폴더를 zip 으로 묶어 cache/exports 에 저장 (공유용). */
    fun zip(ctx: Context, dir: File): File {
        val outDir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val out = File(outDir, "pipeflow_${dir.name}.zip")
        ZipOutputStream(FileOutputStream(out)).use { zos ->
            dir.listFiles()?.forEach { f ->
                zos.putNextEntry(ZipEntry("${dir.name}/${f.name}"))
                FileInputStream(f).use { it.copyTo(zos, 1 shl 16) }
                zos.closeEntry()
            }
        }
        return out
    }
}
