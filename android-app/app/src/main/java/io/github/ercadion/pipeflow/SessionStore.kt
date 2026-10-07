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
 * 측정 세션 저장소: <앱 외부 저장소>/sessions/<촬영날짜 yyyyMMdd>/<촬영시각 HHmmss>_v<앱버전>/
 *   예: sessions/20261007/192009_v0.3.5/   (날짜 폴더 하나에 그날 측정들이 모임)
 *   측정 이름(id) = 날짜_시각_v버전 (예: 20261007_192009_v0.3.5) — 목록·zip·PC 도구에서 사용
 *   앱 버전은 build.gradle.kts 의 versionName (BuildConfig.VERSION_NAME) 이 자동으로 붙음
 *   예전(v0.3.4 이전) 측정은 sessions/<yyyyMMdd_HHmmss>/ 그대로 두고 함께 보여 줌
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
        val now = Date()
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(now)
        val time = SimpleDateFormat("HHmmss", Locale.US).format(now)
        val dayDir = File(root(ctx), day)
        var dir = File(dayDir, "${time}_v${safe(BuildConfig.VERSION_NAME)}")
        var k = 2
        while (dir.exists()) dir = File(dayDir, "${time}_v${safe(BuildConfig.VERSION_NAME)}_$k").also { k++ }  // 같은 초에 두 번
        return dir.apply { mkdirs() }
    }

    private fun isSession(f: File) = f.isDirectory && File(f, "meta.json").exists()

    /** 모든 측정 (날짜 폴더 안 + 예전 형식), 최근 것부터 */
    fun list(ctx: Context): List<File> {
        val out = ArrayList<File>()
        root(ctx).listFiles()?.forEach { f ->
            if (isSession(f)) out += f                                   // 예전 형식 sessions/yyyyMMdd_HHmmss
            else if (f.isDirectory) f.listFiles()?.filter { isSession(it) }?.let { out += it }   // sessions/yyyyMMdd/HHmmss_v…
        }
        return out.sortedByDescending { id(it) }
    }

    /** 측정 이름: 날짜 폴더 안이면 '날짜_시각_v버전', 예전 형식이면 폴더 이름 그대로 */
    fun id(dir: File): String {
        val p = dir.parentFile
        return if (p != null && p.name.matches(Regex("\\d{8}")) && !isSession(p)) "${p.name}_${dir.name}" else dir.name
    }

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

    /** 저장소 기준 상대 경로 (예: 20261007/192009_v0.3.5) */
    fun rel(ctx: Context, dir: File): String =
        dir.canonicalPath.removePrefix(root(ctx).canonicalPath).trimStart(File.separatorChar).replace(File.separatorChar, '/')

    fun safe(v: String) = v.replace(Regex("[^\\w.\\-]"), "_")

    /** 폴더 이름 끝의 _v<버전> (없으면 meta.json 의 app_version, 예전 측정) */
    fun versionOf(dir: File): String? =
        Regex("_v([\\w.\\-]+)$").find(dir.name)?.groupValues?.get(1)
            ?.substringBefore('_')
            ?: readJson(File(dir, "meta.json"))?.optString("app_version")?.takeIf { it.isNotEmpty() }

    fun sizeOf(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** 측정 폴더 삭제 (저장소 안의 측정 폴더만) */
    fun delete(ctx: Context, dir: File): Boolean {
        val rootPath = root(ctx).canonicalPath
        if (!isSession(dir) || !dir.canonicalPath.startsWith(rootPath + File.separator)) return false
        val parent = dir.parentFile
        val ok = dir.deleteRecursively()
        // 비게 된 날짜 폴더 정리
        if (ok && parent != null && parent.canonicalPath != rootPath && parent.listFiles()?.isEmpty() == true) parent.delete()
        return ok
    }

    /** 세션 폴더 하나를 zip 으로 (공유용) */
    fun zip(ctx: Context, dir: File): File = zipMany(ctx, listOf(dir), "pipeflow_${id(dir)}.zip") { _, _ -> }

    /**
     * 여러 세션 폴더를 zip 하나로 묶어 cache/exports 에 저장 (공유용). zip 안 구조: <날짜>/<시각_v버전>/<파일> (앱 저장 구조 그대로)
     * 1시간 지난 예전 내보내기 파일은 정리. onProgress(완료 세션 수, 전체)
     */
    fun zipMany(ctx: Context, dirs: List<File>, name: String? = null, onProgress: (Int, Int) -> Unit): File {
        val outDir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val now = System.currentTimeMillis()
        outDir.listFiles()?.filter { now - it.lastModified() > 3_600_000L }?.forEach { it.delete() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val out = File(outDir, name ?: "pipeflow_${dirs.size}sessions_${ts}_v${safe(BuildConfig.VERSION_NAME)}.zip")
        ZipOutputStream(FileOutputStream(out)).use { zos ->
            zos.setLevel(1)   // 원본 영상(.y)이 커서 빠른 압축
            dirs.forEachIndexed { i, dir ->
                dir.listFiles()?.filter { it.isFile }?.forEach { f ->
                    zos.putNextEntry(ZipEntry("${rel(ctx, dir)}/${f.name}"))
                    FileInputStream(f).use { it.copyTo(zos, 1 shl 16) }
                    zos.closeEntry()
                }
                onProgress(i + 1, dirs.size)
            }
        }
        return out
    }
}
