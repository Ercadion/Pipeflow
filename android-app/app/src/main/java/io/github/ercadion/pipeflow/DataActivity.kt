package io.github.ercadion.pipeflow

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.tabs.TabLayout
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 측정 데이터 관리 (메뉴 → 측정 데이터)
 *  - 앱 저장소의 측정 목록: 날짜·시각, 앱 버전(폴더 이름 끝 _v…), 수심·유속, 저장 전/수정됨/실측값 여부, 크기
 *  - 체크해서 선택 → 선택한 측정을 zip 하나로 내보내기(원본 + 엑셀), 엑셀만 내보내기(수치만), 삭제
 *  - 항목 누르기 → 결과 화면
 */
class DataActivity : AppCompatActivity() {
    private class Item(val dir: File, val s: SessionSummary) {
        var checked = false
        val bytes get() = s.bytes
    }

    private var all: List<Item> = emptyList()
    private var tab = 0          // 0 전체 · 1 측정 · 2 정기 기록

    private lateinit var list: ListView
    private lateinit var txtSummary: TextView
    private lateinit var btnAll: Button
    private lateinit var btnExport: Button
    private lateinit var btnDelete: Button
    private lateinit var btnExcel: Button
    private lateinit var progress: ProgressBar
    private val worker = Executors.newSingleThreadExecutor()
    private var items: List<Item> = emptyList()
    @Volatile private var busy = false

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val v = convert ?: LayoutInflater.from(parent.context).inflate(R.layout.item_session, parent, false)
            val it = items[p]
            val cb = v.findViewById<CheckBox>(R.id.chk)
            cb.setOnCheckedChangeListener(null)
            cb.isChecked = it.checked
            cb.setOnCheckedChangeListener { _, c -> it.checked = c; updateSummary() }
            v.findViewById<TextView>(R.id.txtDate).text = it.s.date
            v.findViewById<TextView>(R.id.txtTitle).text = it.s.title
            v.findViewById<TextView>(R.id.txtDetail).text = it.s.line
            v.findViewById<TextView>(R.id.txtFlags).text =
                (it.s.flags + String.format(Locale.US, "%.1f MB", it.s.bytes / 1e6)).joinToString(" · ")
            ThumbCache.load(this@DataActivity, it.dir, v.findViewById(R.id.imgThumb))
            return v
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data)
        title = "측정 기록"
        NavBar.setup(this, R.id.nav_data)
        val tabs = findViewById<TabLayout>(R.id.tabs)
        listOf("전체", "측정", "정기 기록").forEach { tabs.addTab(tabs.newTab().setText(it)) }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) { tab = t.position; applyFilter() }
            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) {}
        })
        list = findViewById(R.id.list)
        txtSummary = findViewById(R.id.txtSummary)
        btnAll = findViewById(R.id.btnAll)
        btnExport = findViewById(R.id.btnExport)
        btnDelete = findViewById(R.id.btnDelete)
        btnExcel = findViewById(R.id.btnExcel)
        progress = findViewById(R.id.progress)
        progress.visibility = View.GONE
        list.adapter = adapter
        list.setOnItemClickListener { _, _, p, _ ->
            val d = items[p].dir
            if (SessionStore.isMonitor(d))
                startActivity(Intent(this, MonitorViewActivity::class.java).putExtra(MonitorViewActivity.EXTRA_DIR, d.absolutePath))
            else
                startActivity(Intent(this, ResultActivity::class.java).putExtra(ResultActivity.EXTRA_DIR, d.absolutePath))
        }
        btnAll.setOnClickListener {
            val all = items.isNotEmpty() && items.all { it.checked }
            items.forEach { it.checked = !all }
            adapter.notifyDataSetChanged(); updateSummary()
        }
        btnExport.setOnClickListener { export() }
        btnDelete.setOnClickListener { confirmDelete() }
        btnExcel.setOnClickListener { exportExcel() }
    }

    override fun onResume() {
        super.onResume()
        if (!busy) load()
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }

    private fun load() {
        val keep = all.filter { it.checked }.map { it.dir.absolutePath }.toSet()
        all = SessionStore.list(this).map { Item(it, SessionSummary.of(it)).also { i -> i.checked = i.dir.absolutePath in keep } }
        applyFilter()
    }

    private fun applyFilter() {
        items = when (tab) { 1 -> all.filter { !it.s.monitor }; 2 -> all.filter { it.s.monitor }; else -> all }
        adapter.notifyDataSetChanged()
        updateSummary()
    }

    private fun updateSummary() {
        val sel = items.filter { it.checked }
        txtSummary.text = String.format(Locale.US, "측정 %d건 · 전체 %.1f MB · 선택 %d건 (%.1f MB)",
            items.size, items.sumOf { it.bytes } / 1e6, sel.size, sel.sumOf { it.bytes } / 1e6)
        btnExport.isEnabled = sel.isNotEmpty() && !busy
        btnDelete.isEnabled = sel.isNotEmpty() && !busy
        btnExcel.isEnabled = sel.isNotEmpty() && !busy
        btnAll.text = if (items.isNotEmpty() && items.all { it.checked }) "선택 해제" else "전체 선택"
    }

    private fun setBusy(b: Boolean) {
        busy = b
        progress.visibility = if (b) View.VISIBLE else View.GONE
        btnAll.isEnabled = !b
        updateSummary()
    }

    /** 선택한 측정 → zip 하나 → 공유(드라이브·메일·메신저 등) */
    private fun export() {
        val sel = items.filter { it.checked }.map { it.dir }
        if (sel.isEmpty()) return
        setBusy(true)
        progress.max = sel.size; progress.progress = 0
        txtSummary.text = "엑셀 만드는 중… (처음 실행 시 Python 로딩에 몇 초 걸림)"
        worker.execute {
            val res = runCatching {
                SessionStore.zipMany(this, sel) { done, total ->
                    runOnUiThread { progress.progress = done; txtSummary.text = "압축 중… $done / $total" }
                }
            }
            runOnUiThread {
                setBusy(false)
                res.onSuccess { zip ->
                    val uri = FileProvider.getUriForFile(this, "$packageName.files", zip)
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, String.format(Locale.US, "측정 %d건 내보내기 (%.1f MB)", sel.size, zip.length() / 1e6)))
                }.onFailure { e ->
                    Toast.makeText(this, "압축 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** 선택한 측정 → 엑셀(.xlsx) 하나 → 공유 (원본 영상 없이 수치만, 회사 분석용) */
    private fun exportExcel() {
        val sel = items.filter { it.checked }.map { it.dir }
        if (sel.isEmpty()) return
        setBusy(true)
        progress.isIndeterminate = true
        txtSummary.text = "엑셀 만드는 중… (처음 실행 시 Python 로딩에 몇 초 걸림)"
        worker.execute {
            val res = runCatching { SessionStore.buildExcel(this, sel).first }
            runOnUiThread {
                progress.isIndeterminate = false
                setBusy(false)
                res.onSuccess { xlsx ->
                    val uri = FileProvider.getUriForFile(this, "$packageName.files", xlsx)
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "측정 ${sel.size}건 엑셀 내보내기"))
                }.onFailure { e ->
                    Toast.makeText(this, "엑셀 만들기 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun confirmDelete() {
        val sel = items.filter { it.checked }
        if (sel.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("측정 ${sel.size}건 삭제")
            .setMessage("선택한 측정의 원본 영상·결과·실측값이 모두 지워지고 되돌릴 수 없습니다. 필요하면 먼저 내보내기 하세요.")
            .setPositiveButton("삭제") { _, _ ->
                val failed = sel.count { !SessionStore.delete(this, it.dir) }
                if (failed > 0) Toast.makeText(this, "${failed}건 삭제 실패", Toast.LENGTH_SHORT).show()
                load()
            }
            .setNegativeButton("취소", null).show()
    }
}
