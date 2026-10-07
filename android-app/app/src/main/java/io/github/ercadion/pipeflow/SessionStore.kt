package io.github.ercadion.pipeflow

import android.content.Context
import org.json.JSONArray
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
 *   meta.json, still.y, frames.y          촬영 원본
 *   result.json        가장 최근 분석 결과 (다시 계산할 때마다 바뀜)
 *   result_auto.json   처음 자동 분석 결과 (사람 수정 전, 한 번 쓰면 다시 안 바뀜)
 *   runs.json          분석 회차 기록 [{index, kind(auto|manual_waterline|rim_redetect), time_ms, inputs, result}]
 *   labels.json        사람 판단: 자동/최종 피드백, 수정 이력(edits), 메모
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

    /** 분석 회차 하나를 runs.json 에 추가하고 회차 번호(1부터) 반환 */
    fun appendRun(dir: File, entry: JSONObject): Int {
        val f = File(dir, "runs.json")
        val arr = if (f.exists()) runCatching { JSONArray(f.readText()) }.getOrNull() ?: JSONArray() else JSONArray()
        val idx = arr.length() + 1
        entry.put("index", idx)
        arr.put(entry)
        f.writeText(arr.toString(1))
        return idx
    }

    fun runCount(dir: File): Int {
        val f = File(dir, "runs.json")
        return if (f.exists()) runCatching { JSONArray(f.readText()).length() }.getOrNull() ?: 0 else 0
    }

    /** labels.json 의 edits 배열에 수정 기록 추가 */
    fun appendEdit(dir: File, edit: JSONObject) = writeLabels(dir) { lb ->
        val arr = lb.optJSONArray("edits") ?: JSONArray()
        edit.put("time_ms", System.currentTimeMillis())
        arr.put(edit)
        lb.put("edits", arr)
    }

    /** 세션 폴더를 zip 으로 묶어 cache/exports 에 저장 (공유용). */
    fun zip(ctx: Context, dir: File): File {
        val outDir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val out = File(outDir, "pipeflow_${dir.name}.zip")
        ZipOutputStream(FileOutputStream(out)).use { zos ->
            dir.listFiles()?.filter { it.isFile }?.forEach { f ->
                zos.putNextEntry(ZipEntry("${dir.name}/${f.name}"))
                FileInputStream(f).use { it.copyTo(zos, 1 shl 16) }
                zos.closeEntry()
            }
        }
        return out
    }
}
