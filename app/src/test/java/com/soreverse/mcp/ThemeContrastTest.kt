package com.soreverse.mcp

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配色对比度守卫。
 *
 * 为什么要单测：配色是「派生」出来的 —— 用户换一个强调色，整套 primary / tertiary /
 * secondary / 容器 / 表面都会重算。肉眼在手机上逐个配色试不可行（10 个色 × 明暗），
 * 而对比度是可计算的：这里把 WCAG 相对亮度算法搬进来，对每个强调色断言每个
 * 「文字槽 × 表面槽」都过 4.5:1（正文标准）。
 *
 * v1.5.42 修的正是这类问题：浅色下黄色当文字只有 2.44:1、橙色 3.15、绿色 3.25 ——
 * 全都过不了 AA，但只要没人量就看不出来（默认粉色恰好达标）。
 */
class ThemeContrastTest {

    private val accents = listOf(
        "blue", "teal", "indigo", "purple", "green",
        "orange", "red", "yellow", "pink", "mono",
    )

    /** WCAG 2.1 对比度：(L1+0.05)/(L2+0.05)，L 为相对亮度。 */
    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        val hi = kotlin.math.max(la, lb)
        val lo = kotlin.math.min(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun lightScheme(name: String, highContrast: Boolean = false): ColorScheme =
        appLightColors(AppPalette.accent(name, dark = false), highContrast, name)

    private fun darkScheme(name: String, highContrast: Boolean = false): ColorScheme =
        appDarkColors(
            AppPalette.accent(name, dark = true),
            pureBlack = false,
            highContrast = highContrast,
            accentName = name,
        )

    /** 页面/卡片表面 —— 文字实际会落在这些底上。 */
    private fun surfaces(cs: ColorScheme): List<Pair<String, Color>> = listOf(
        "background" to cs.background,
        "surface" to cs.surface,
        "surfaceContainerLowest" to cs.surfaceContainerLowest,
        "surfaceContainerLow" to cs.surfaceContainerLow,
        "surfaceContainer" to cs.surfaceContainer,
        "surfaceContainerHigh" to cs.surfaceContainerHigh,
        "surfaceContainerHighest" to cs.surfaceContainerHighest,
        "surfaceVariant" to cs.surfaceVariant,
    )

    /** onXxx 只出现在它自己的容器上，不与所有表面配对。 */
    private fun containerPairs(cs: ColorScheme): List<Triple<String, Color, Color>> = listOf(
        Triple("onPrimaryContainer/primaryContainer", cs.onPrimaryContainer, cs.primaryContainer),
        Triple("onSecondaryContainer/secondaryContainer", cs.onSecondaryContainer, cs.secondaryContainer),
        Triple("onTertiaryContainer/tertiaryContainer", cs.onTertiaryContainer, cs.tertiaryContainer),
        Triple("onErrorContainer/errorContainer", cs.onErrorContainer, cs.errorContainer),
    )

    private fun audit(scheme: ColorScheme, themeName: String): List<String> {
        val fails = mutableListOf<String>()
        // 1) 强调文字色与正文文字色压在任意一层表面上都要读得清
        val plainInk = listOf(
            "primary" to scheme.primary,
            "secondary" to scheme.secondary,
            "tertiary" to scheme.tertiary,
            "onSurface" to scheme.onSurface,
            "onSurfaceVariant" to scheme.onSurfaceVariant,
        )
        for ((inkName, ink) in plainInk) {
            for ((surfaceName, surface) in surfaces(scheme)) {
                val r = contrast(ink, surface)
                if (r < 4.5) fails += "$themeName · $inkName on $surfaceName = ${fmt(r)}"
            }
        }
        // 2) 容器上的文字
        for ((label, ink, container) in containerPairs(scheme)) {
            val r = contrast(ink, container)
            if (r < 4.5) fails += "$themeName · $label = ${fmt(r)}"
        }
        // 3) 实心主色 / 互补色 / 错误色上的文字（按钮、FAB、对比按钮）
        for ((label, ink, fill) in listOf(
            Triple("onPrimary/primary", scheme.onPrimary, scheme.primary),
            Triple("onTertiary/tertiary", scheme.onTertiary, scheme.tertiary),
            Triple("onError/error", scheme.onError, scheme.error),
        )) {
            val r = contrast(ink, fill)
            if (r < 4.5) fails += "$themeName · $label = ${fmt(r)}"
        }
        return fails
    }

    private fun fmt(v: Double): String = String.format("%.2f:1", v)

    @Test
    fun everyAccentTextMeetsAaInLightScheme() {
        val fails = accents.flatMap { audit(lightScheme(it), "light/$it") }
        assertTrue(
            "浅色方案有 ${fails.size} 处文字对比度低于 4.5:1：\n" + fails.joinToString("\n"),
            fails.isEmpty(),
        )
    }

    @Test
    fun everyAccentTextMeetsAaInDarkScheme() {
        val fails = accents.flatMap { audit(darkScheme(it), "dark/$it") }
        assertTrue(
            "深色方案有 ${fails.size} 处文字对比度低于 4.5:1：\n" + fails.joinToString("\n"),
            fails.isEmpty(),
        )
    }

    /** 高对比度开关打开时不能比普通模式更差。 */
    @Test
    fun highContrastIsNotWorseThanNormal() {
        val fails = mutableListOf<String>()
        for (name in accents) {
            val normal = lightScheme(name)
            val high = lightScheme(name, highContrast = true)
            val checks = listOf(
                Triple("onSurface/background", normal.onSurface to normal.background, high.onSurface to high.background),
                Triple(
                    "onSurfaceVariant/background",
                    normal.onSurfaceVariant to normal.background,
                    high.onSurfaceVariant to high.background,
                ),
                Triple("outline/background", normal.outline to normal.background, high.outline to high.background),
            )
            for ((label, n, h) in checks) {
                val rn = contrast(n.first, n.second)
                val rh = contrast(h.first, h.second)
                if (rh + 1e-6 < rn) fails += "light/$name · $label 高对比 ${fmt(rh)} < 普通 ${fmt(rn)}"
            }
        }
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
    }

    /** 描边必须看得见：1dp 线对表面至少要有 1.15:1（低于这个就"有线等于没线"）。 */
    @Test
    fun outlineStaysVisibleOnEverySurface() {
        val fails = mutableListOf<String>()
        for (name in accents) {
            for ((mode, cs) in listOf("light" to lightScheme(name), "dark" to darkScheme(name))) {
                for ((surfaceName, surface) in listOf(
                    "background" to cs.background,
                    "surfaceContainer" to cs.surfaceContainer,
                    "surfaceContainerHigh" to cs.surfaceContainerHigh,
                    "surfaceVariant" to cs.surfaceVariant,
                )) {
                    val r = contrast(cs.outlineVariant, surface)
                    if (r < 1.15) fails += "$mode/$name · outlineVariant on $surfaceName = ${fmt(r)}"
                }
            }
        }
        assertTrue("描边在这些组合下看不见：\n" + fails.joinToString("\n"), fails.isEmpty())
    }
}
