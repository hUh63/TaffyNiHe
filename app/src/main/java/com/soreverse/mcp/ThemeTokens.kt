package com.soreverse.mcp

import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 调色板 —— 数值全部取自 Exbin（com.exbin.app）`res/values/colors.xml` 的 preset_*。
 *
 * 浅色用 preset_*_primary（蓝 #1A6EF0），深色用 md_theme_dark_primary（绿 #66D39A）——
 * Exbin 从 v1.4.6 起深色就是「深绿底 + 绿主色」，这是两套 UI 观感差异最大的一处。
 */
internal object AppPalette {
    val blue = Color(0xFF1A6EF0)
    val teal = Color(0xFF006C4C)
    val indigo = Color(0xFF6750A4)
    val purple = Color(0xFF6750A4)
    val green = Color(0xFF006C4C)
    val orange = Color(0xFFC56500)
    val red = Color(0xFFC00000)
    val yellow = Color(0xFFC56500)
    val pink = Color(0xFF9D3A6E)
    val mono = Color(0xFF7E8E80)

    fun accent(name: String, dark: Boolean): Color = when (name) {
        "blue" -> if (dark) Color(0xFF66D39A) else Color(0xFF1A6EF0)
        "teal" -> if (dark) Color(0xFF66D39A) else Color(0xFF006C4C)
        "indigo" -> if (dark) Color(0xFFB9A8E0) else Color(0xFF6750A4)
        "purple" -> if (dark) Color(0xFFCBB6E8) else Color(0xFF6750A4)
        "green" -> if (dark) Color(0xFF66D39A) else Color(0xFF006C4C)
        "orange" -> if (dark) Color(0xFFFFB868) else Color(0xFFC56500)
        "red" -> if (dark) Color(0xFFFFB4AB) else Color(0xFFC00000)
        "yellow" -> if (dark) Color(0xFFF5C84C) else Color(0xFFC56500)
        "pink" -> if (dark) Color(0xFFF0A8C8) else Color(0xFF9D3A6E)
        "mono" -> if (dark) Color(0xFFB7CCB8) else Color(0xFF44474E)
        else -> if (dark) Color(0xFF66D39A) else Color(0xFF1A6EF0)
    }
}

/** 形状 token：全局统一圆角档位（原先散落 2/4/6/8/10/12/14/18/20/24/26/999，收敛为 6 档）。 */
internal object AppShape {
    val xs = 4.dp        // 极小元素：徽标/分隔/微控件
    val sm = 8.dp        // 小控件：chip/按钮/输入框
    val md = 12.dp       // 常规：卡片/列表项
    val lg = 16.dp       // 大容器：面板/弹层
    val xl = 24.dp       // 特大：底部弹层/模态
    val pill = 999.dp    // 胶囊
}

/** 文字尺寸 token：收敛原先 8~24sp 共 11 档为 6 档语义层级。 */
internal object AppText {
    // sp 本身已经随系统字体缩放；这里再乘 App 设置里的字号系数。
    // 用 State 承载，切换字号后所有读到 AppText 的界面才会真的重组（原来字号设置只影响 typography，
    // 而这些显式 fontSize 的文本完全不变，界面层级会错乱）。
    private var scaleState by mutableStateOf(1f)

    var scale: Float
        get() = scaleState
        set(value) {
            if (value > 0f && value != scaleState) scaleState = value
        }

    private fun scaled(base: Float) = (base * scaleState).sp

    // 字号对齐 Exbin 的 M3 textAppearance：LabelSmall 11 / BodySmall 12 /
    // BodyMedium 14 / TitleSmall 14 / TitleMedium 16 / 数值展示 22。
    val label get() = scaled(11f)        // 辅助标签/说明（LabelSmall）
    val body get() = scaled(12f)         // 正文（BodySmall）
    val bodyStrong get() = scaled(14f)   // 强调正文/次级标题（BodyMedium / TitleSmall）
    val title get() = scaled(14f)        // 区块标题（TitleSmall）
    val headline get() = scaled(16f)     // 页面标题（TitleMedium，Exbin toolbar）
    val display get() = scaled(22f)      // 数字/大标题
}

internal data class UiMetrics(
    val pagePad: androidx.compose.ui.unit.Dp,
    val sectionGap: androidx.compose.ui.unit.Dp,
    val rowPadV: androidx.compose.ui.unit.Dp,
    val cardRadius: androidx.compose.ui.unit.Dp,
    val controlRadius: androidx.compose.ui.unit.Dp,
)

internal fun uiMetrics(density: String, corner: String): UiMetrics {
    val pagePad = when (density) {
        "compact" -> 12.dp
        "spacious" -> 20.dp
        else -> 16.dp
    }
    val sectionGap = when (density) {
        "compact" -> 12.dp
        "spacious" -> 22.dp
        else -> 16.dp
    }
    val rowPadV = when (density) {
        "compact" -> 8.dp
        "spacious" -> 14.dp
        else -> 11.dp
    }
    // Exbin 卡片风：列表项 12dp / 组卡 20dp，档位随之对齐
    val cardRadius = when (corner) {
        "small" -> 12.dp
        "large" -> 20.dp
        "xlarge" -> 24.dp
        else -> 16.dp
    }
    val controlRadius = when (corner) {
        "small" -> 8.dp
        "large" -> 14.dp
        "xlarge" -> 18.dp
        else -> 11.dp
    }
    return UiMetrics(pagePad, sectionGap, rowPadV, cardRadius, controlRadius)
}

internal fun textScaleFactor(textScale: String): Float = when (textScale) {
    "xsmall" -> 0.88f
    "small" -> 0.94f
    "large" -> 1.08f
    "xlarge" -> 1.16f
    else -> 1f
}

internal fun TextStyle.scaledBy(scale: Float): TextStyle = copy(
    fontSize = (fontSize.value * scale).sp,
    lineHeight = (lineHeight.value * scale).sp,
)

internal fun scaledTypography(scale: Float): Typography {
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.scaledBy(scale),
        displayMedium = base.displayMedium.scaledBy(scale),
        displaySmall = base.displaySmall.scaledBy(scale),
        headlineLarge = base.headlineLarge.scaledBy(scale),
        headlineMedium = base.headlineMedium.scaledBy(scale),
        headlineSmall = base.headlineSmall.scaledBy(scale),
        titleLarge = base.titleLarge.scaledBy(scale),
        titleMedium = base.titleMedium.scaledBy(scale),
        titleSmall = base.titleSmall.scaledBy(scale),
        bodyLarge = base.bodyLarge.scaledBy(scale),
        bodyMedium = base.bodyMedium.scaledBy(scale),
        bodySmall = base.bodySmall.scaledBy(scale),
        labelLarge = base.labelLarge.scaledBy(scale),
        labelMedium = base.labelMedium.scaledBy(scale),
        labelSmall = base.labelSmall.scaledBy(scale),
    )
}

/**
 * 浅色方案 —— 逐 token 取自 Exbin `preset_blue_*`（Material Theme Builder 对 #1A6EF0 的输出）。
 *
 * surfaceContainer* 在 Exbin 的 XML 里没有显式定义（M3 组件按 tonal palette 派生），
 * 这里用同一套 neutral 色调的标准档位补齐，保证「卡片比背景亮/暗一档」的关系一致。
 * 卡片底色按 Exbin `item_detail.xml` 用的是 **surfaceVariant**，不是 surface。
 */
internal fun appLightColors(accent: Color, highContrast: Boolean) = lightColorScheme(
    primary = accent,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9E2FF),
    onPrimaryContainer = Color(0xFF001B3E),
    secondary = Color(0xFF535F70),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD7E3F7),
    onSecondaryContainer = Color(0xFF101C2B),
    tertiary = Color(0xFF6B5778),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF2DAFF),
    onTertiaryContainer = Color(0xFF251431),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFEFBFF),
    onBackground = if (highContrast) Color(0xFF000000) else Color(0xFF1B1B1F),
    surface = Color(0xFFFEFBFF),
    onSurface = if (highContrast) Color(0xFF000000) else Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = if (highContrast) Color(0xFF2A2D33) else Color(0xFF44474E),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F2FA),
    surfaceContainer = Color(0xFFEFEDF4),
    surfaceContainerHigh = Color(0xFFE9E7EF),
    surfaceContainerHighest = Color(0xFFE3E1E9),
    outline = if (highContrast) Color(0xFF44474E) else Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0),
    inverseSurface = Color(0xFF303034),
    inverseOnSurface = Color(0xFFF2F0F4),
    inversePrimary = Color(0xFFB0C6FF),
    surfaceTint = accent,
)

/**
 * 深色方案 —— 逐 token 取自 Exbin `md_theme_dark_*`（深绿底 #0F1F15 + 绿主色 #66D39A）。
 *
 * pureBlack 仍保留：用户显式要纯黑时把背景压到 #000000，但卡片/描边仍用 Exbin 的绿灰档位，
 * 否则纯黑下所有层次都会消失。
 */
internal fun appDarkColors(accent: Color, pureBlack: Boolean, highContrast: Boolean) = darkColorScheme(
    primary = accent,
    onPrimary = Color(0xFF003824),
    primaryContainer = Color(0xFF005236),
    onPrimaryContainer = Color(0xFF88F0B6),
    secondary = Color(0xFFB7CCB8),
    onSecondary = Color(0xFF223527),
    secondaryContainer = Color(0xFF384B3D),
    onSecondaryContainer = Color(0xFFD2E8D3),
    tertiary = Color(0xFFA4C7AE),
    onTertiary = Color(0xFF0E2F18),
    tertiaryContainer = Color(0xFF26472F),
    onTertiaryContainer = Color(0xFFBFE3C7),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = if (pureBlack) Color(0xFF000000) else Color(0xFF0F1F15),
    onBackground = if (highContrast) Color(0xFFFFFFFF) else Color(0xFFD5E5D8),
    surface = if (pureBlack) Color(0xFF000000) else Color(0xFF0F1F15),
    onSurface = if (highContrast) Color(0xFFFFFFFF) else Color(0xFFD5E5D8),
    surfaceVariant = Color(0xFF2A3A2E),
    onSurfaceVariant = if (highContrast) Color(0xFFD5E5D8) else Color(0xFFB7CCB8),
    surfaceContainerLowest = Color(0xFF0A1410),
    surfaceContainerLow = Color(0xFF17251C),
    surfaceContainer = Color(0xFF1B2A20),
    surfaceContainerHigh = Color(0xFF25342A),
    surfaceContainerHighest = Color(0xFF2F3E34),
    outline = if (highContrast) Color(0xFFB7CCB8) else Color(0xFF7E8E80),
    outlineVariant = Color(0xFF3D4D40),
    inverseSurface = Color(0xFFD5E5D8),
    inverseOnSurface = Color(0xFF0F1F15),
    inversePrimary = Color(0xFF1A6EF0),
    surfaceTint = accent,
)

internal fun statusSuccess(dark: Boolean = false): Color = if (dark) Color(0xFF30D158) else Color(0xFF34C759)
internal fun statusError(dark: Boolean = false): Color = if (dark) Color(0xFFFF453A) else Color(0xFFFF3B30)
internal fun statusWarning(dark: Boolean = false): Color = if (dark) Color(0xFFFF9F0A) else Color(0xFFFF9500)

internal object AppleColors {
    val systemBlue = AppPalette.blue
    val systemBlueDark = AppPalette.blue
    val systemGreen = AppPalette.green
    val systemGreenDark = AppPalette.green
    val systemRed = AppPalette.red
    val systemRedDark = AppPalette.red
    val systemOrange = AppPalette.orange
    val systemOrangeDark = AppPalette.orange
    val systemIndigo = AppPalette.indigo
    val systemTeal = AppPalette.teal
    val systemPurple = AppPalette.purple
    object Light {
        val background = Color(0xFFF2F2F7)
        val secondaryBackground = Color(0xFFFFFFFF)
        val tertiaryBackground = Color(0xFFF2F2F7)
        val groupedBackground = Color(0xFFF2F2F7)
        val card = Color(0xFFFFFFFF)
        val fill = Color(0xFFE5E5EA)
        val fillSecondary = Color(0xFFF2F2F7)
        val separator = Color(0xFFC6C6C8)
        val label = Color(0xFF1C1C1E)
        val secondaryLabel = Color(0xFF636366)
        val tertiaryLabel = Color(0xFF8E8E93)
        val primary = AppPalette.blue
        val onPrimary = Color.White
        val success = AppPalette.green
        val error = AppPalette.red
        val warning = AppPalette.orange
        val glass = Color(0xFFFFFFFF)
        val glassStrong = Color(0xFFFFFFFF)
        val glassStroke = Color(0x33C6C6C8)
    }
    object Dark {
        val background = Color(0xFF000000)
        val secondaryBackground = Color(0xFF1C1C1E)
        val tertiaryBackground = Color(0xFF2C2C2E)
        val groupedBackground = Color(0xFF000000)
        val card = Color(0xFF1C1C1E)
        val fill = Color(0xFF3A3A3C)
        val fillSecondary = Color(0xFF2C2C2E)
        val separator = Color(0xFF38383A)
        val label = Color(0xFFF5F5F7)
        val secondaryLabel = Color(0xFF8E8E93)
        val tertiaryLabel = Color(0xFF636366)
        val primary = AppPalette.blue
        val onPrimary = Color.White
        val success = AppPalette.green
        val error = AppPalette.red
        val warning = AppPalette.orange
        val glass = Color(0xFF1C1C1E)
        val glassStrong = Color(0xFF1C1C1E)
        val glassStroke = Color(0x33FFFFFF)
    }
}

internal fun appleLightColors() = appLightColors(AppPalette.blue, false)
internal fun appleDarkColors() = appDarkColors(AppPalette.blue, true, false)
internal fun appleSuccess(dark: Boolean = false): Color = statusSuccess(dark)
internal fun appleError(dark: Boolean = false): Color = statusError(dark)
internal fun appleWarning(dark: Boolean = false): Color = statusWarning(dark)

internal val LocalUiMetrics = staticCompositionLocalOf {
    uiMetrics("comfortable", "medium")
}
internal val LocalReduceMotion = staticCompositionLocalOf { false }
