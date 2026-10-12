package io.github.ercadion.pipeflow

import android.app.Activity
import android.content.Intent
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * 하단 탭 (홈 · 측정 · 기록 · 설정) — 화면마다 view_bottom_nav 를 넣고 setup 호출
 * 홈(MainActivity)이 항상 맨 아래에 있고, 다른 탭은 그 위에 하나만 (탭을 바꾸면 이전 탭 화면은 닫음)
 */
object NavBar {
    fun setup(a: Activity, selected: Int) {
        val nav = a.findViewById<BottomNavigationView>(R.id.bottomNav) ?: return
        nav.selectedItemId = selected
        nav.setOnItemSelectedListener { item ->
            if (item.itemId == selected) return@setOnItemSelectedListener true
            val target = when (item.itemId) {
                R.id.nav_measure -> MeasureActivity::class.java
                R.id.nav_data -> DataActivity::class.java
                R.id.nav_settings -> SettingsActivity::class.java
                else -> MainActivity::class.java
            }
            if (target != MainActivity::class.java) {
                a.startActivity(Intent(a, target))
            } else {
                a.startActivity(Intent(a, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            @Suppress("DEPRECATION") a.overridePendingTransition(0, 0)
            if (a !is MainActivity) a.finish()
            true
        }
    }
}
