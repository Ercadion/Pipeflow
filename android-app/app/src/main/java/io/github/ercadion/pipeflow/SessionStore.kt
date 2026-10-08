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
 *
 * 측정 폴더 = 학습용 데이터 기록 (v0.3.6~). 저장 후에는 원본·결과를 바꾸지 않음
 *   meta.json, still.y, frames.y   촬영 원본
 *   result.json             자동 분석 결과 (촬영 직후 1회, 이후 다시 쓰지 않음)
 *   result_corrected.json   저장 전 확인 단계에서 사람이 고친 결과 (있을 때만, correction 에 무엇을 고쳤는지)
 *   annotations.json        실측값·메모 (덧붙이기만 하는 기록: 입력 시각·앱 버전과 함께, 지운 값도 이력으로 남음)
 *   record.json             저장(확정) 정보: 시각, 앱 버전·커밋, 고침 여부, 파일별 SHA-256 (변조 확인용)
 *   .review_pending         저장 전 확인 중 표시 (저장하면 사라짐, 내보내기에 포함 안 됨)
 * 예전(v0.3.4~0.3.5) 측정: result_auto.json(자동) + result.json(마지막으로 고친 결과) + labels.json — 읽기만 함
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

    // ------------------------------------------------------------------ 측정 기록 (데이터)
    const val PENDING = ".review_pending"

    /** 저장 전 확인 중인 측정 (촬영 직후 ~ 저장 버튼) */
    fun isPending(dir: File) = File(dir, PENDING).exists()
    fun markPending(dir: File) { File(dir, PENDING).writeText("") }

    /** 예전(v0.3.4~0.3.5) 형식인지 */
    fun isLegacy(dir: File) = File(dir, "result_auto.json").exists() || File(dir, "labels.json").exists()

    /** 자동 분석 결과 (사람이 고치기 전) */
    fun autoResult(dir: File): JSONObject? =
        readJson(File(dir, "result_auto.json")) ?: readJson(File(dir, "result.json"))

    /** 사람이 고친 결과 (없으면 null) */
    fun correctedResult(dir: File): JSONObject? {
        readJson(File(dir, "result_corrected.json"))?.let { return it }
        if (File(dir, "result_auto.json").exists()) {          // 예전 형식: result.json 이 마지막으로 고친 결과
            val a = readJson(File(dir, "result_auto.json")); val r = readJson(File(dir, "result.json"))
            if (r != null && r.optBoolean("ok") && differs(a, r)) return r
        }
        return null
    }

    /** 수면선(0.5 px)·테두리(0.5 px) 가 다르면 true */
    fun differs(a: JSONObject?, b: JSONObject?): Boolean {
        if (a == null || b == null) return false
        val wa = waterlineOf(a); val wb = waterlineOf(b)
        if (wa != null && wb != null && (0 until 4).any { kotlin.math.abs(wa[it] - wb[it]) > 0.5 }) return true
        val ea = a.optJSONObject("overlay")?.optJSONObject("ellipse")
        val eb = b.optJSONObject("overlay")?.optJSONObject("ellipse")
        return ea != null && eb != null && listOf("cx", "cy", "a", "b").any { kotlin.math.abs(ea.optDouble(it) - eb.optDouble(it)) > 0.5 }
    }

    /** 결과의 수면선 [x1,y1,x2,y2] (영상 px) */
    fun waterlineOf(r: JSONObject?): DoubleArray? {
        val wl = r?.optJSONObject("overlay")?.optJSONArray("waterline") ?: return null
        return doubleArrayOf(wl.getJSONArray(0).getDouble(0), wl.getJSONArray(0).getDouble(1),
            wl.getJSONArray(1).getDouble(0), wl.getJSONArray(1).getDouble(1))
    }

    val ANNOTATION_FIELDS = listOf("truth_depth_mm", "truth_v_mean_mps", "truth_Q_Lps", "note")

    /** 실측값·메모의 현재 값 (기록을 순서대로 반영, 예전 labels.json 값이 기본) */
    fun annotations(dir: File): JSONObject {
        val cur = JSONObject()
        readJson(File(dir, "labels.json"))?.let { lb -> ANNOTATION_FIELDS.forEach { k -> if (lb.has(k) && lb.optString(k) != "") cur.put(k, lb.get(k)) } }
        readJson(File(dir, "annotations.json"))?.optJSONArray("entries")?.let { arr ->
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val k = e.optString("field")
                if (e.isNull("value")) cur.remove(k) else cur.put(k, e.get("value"))
            }
        }
        return cur
    }

    /** 바뀐 실측값·메모만 기록에 덧붙임 (값 null = 지움). 바뀐 것이 있으면 true */
    fun appendAnnotations(dir: File, values: Map<String, Any?>): Boolean {
        val cur = annotations(dir)
        val changed = values.filter { (k, v) ->
            val old = if (cur.has(k)) cur.get(k) else null
            if (v == null) old != null else old == null || old.toString() != v.toString()
        }
        if (changed.isEmpty()) return false
        val f = File(dir, "annotations.json")
        val o = readJson(f) ?: JSONObject().put("entries", JSONArray())
        val arr = o.optJSONArray("entries") ?: JSONArray().also { o.put("entries", it) }
        val now = System.currentTimeMillis()
        changed.forEach { (k, v) ->
            arr.put(JSONObject().put("time_ms", now).put("app_version", BuildConfig.VERSION_NAME)
                .put("app_build", BuildConfig.GIT_SHA).put("field", k).put("value", v ?: JSONObject.NULL))
        }
        f.writeText(o.toString(1))
        return true
    }

    fun sha256(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = ins.read(buf); if (n <= 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 저장(확정): record.json 작성 + 확인 중 표시 제거. 이후 원본·결과 파일은 바꾸지 않음 */
    fun finalize(dir: File) {
        val sums = JSONObject()
        listOf("meta.json", "still.y", "frames.y", "result.json", "result_corrected.json").forEach { n ->
            File(dir, n).takeIf { it.exists() }?.let { sums.put(n, sha256(it)) }
        }
        val corr = readJson(File(dir, "result_corrected.json"))
        val rec = JSONObject().put("finalized_ms", System.currentTimeMillis())
            .put("app_version", BuildConfig.VERSION_NAME).put("app_build", BuildConfig.GIT_SHA)
            .put("corrected", corr != null).put("sha256", sums)
        corr?.optJSONObject("correction")?.let { rec.put("correction", it) }
        File(dir, "record.json").writeText(rec.toString(1))
        File(dir, PENDING).delete()
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
                dir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }?.forEach { f ->
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
