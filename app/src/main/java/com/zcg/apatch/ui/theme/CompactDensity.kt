package com.zcg.apatch.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * 小屏适配。
 *
 * 目标设备是 320x360 px @ 320dpi，也就是只有 **160x180 dp** 的可用区域：Material
 * 默认的 16dp 边距、56dp 触摸目标和 bodyLarge 在这里会直接把内容顶出屏幕。
 *
 * 与其把每个屏幕的 dp 都改一遍（会破坏大屏机型的观感），这里按可用宽度给整个
 * Compose 子树一个密度系数：dp 数值不变，实际像素按比例缩小。160dp 宽时取 0.78，
 * 相当于把 16dp 边距渲染成 12.5dp、56dp 项渲染成 44dp——接近 Material 在小屏上的
 * 建议值，同时字号也跟着缩，避免文字被裁。
 *
 * 只影响 Compose 布局；系统栏、通知等不受影响。
 */
@Composable
fun CompactDensity(content: @Composable () -> Unit) {
    val config = LocalConfiguration.current
    val density = LocalDensity.current

    val scale = when {
        config.screenWidthDp <= 180 -> 0.78f   // 320x360 @ 320dpi (~160x180 dp)
        config.screenWidthDp <= 240 -> 0.90f   // 小屏手机 / 大号手表
        else -> 1f
    }

    if (scale >= 0.999f) {
        content()
    } else {
        CompositionLocalProvider(
            LocalDensity provides Density(density = density.density * scale, fontScale = density.fontScale)
        ) {
            content()
        }
    }
}
