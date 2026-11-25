package com.zcg.apatch.ui.theme

import android.content.Context
import android.content.res.Configuration

/**
 * 小屏固定密度。
 *
 * 目标设备是 320x360 px @ 320dpi —— 只有 160x180 dp，Material 的 16dp 边距和
 * 56dp 触摸目标会直接把内容顶出屏幕。系统密度不改（仍然是 320），只把这个 App
 * 的资源配置密度设成 200dpi：
 *
 *   320 px / (200/160) = 256 dp 宽，360 px → 288 dp 高
 *
 * 比 320dpi 下的 160x180dp 宽裕得多，也不用挨个改布局里的 dp。
 * 判断条件用「物理宽度 <= 400px 且系统密度 >= 300dpi」，普通手机不受影响。
 */
object ScreenDensity {
    const val TARGET_DPI = 200

    fun wrap(base: Context): Context {
        val dm = base.resources.displayMetrics
        if (dm.widthPixels > 400 || dm.densityDpi < 300) return base

        val cfg = Configuration(base.resources.configuration).apply {
            densityDpi = TARGET_DPI
        }
        return base.createConfigurationContext(cfg)
    }
}
