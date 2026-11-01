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
 * 设计系统 v2 —— Graphite（石墨）。
 *
 * 上一版把 Exbin 的两套观感直接搬了过来：浅色是蓝主色，深色是**深绿底 + 绿主色**
 * （#0F1F15 / #66D39A）。逆向工具的主界面是地址、十六进制、伪 C 和终端，
 * 大面积绿色底会把代码的中性灰衬得发闷，也让"状态色"失去意义 —— 屏幕上到处都是绿。
 * 更麻烦的是深浅两套是两种性格，同一个人切换主题会觉得像换了个 App。
 *
 * 这一版换成一套中性石墨：
 *   · 底色是不带色相倾向的冷灰，代码/十六进制在上面才"站得住"；
 *   · 主色只有一个蓝（#2C6BED / 深色 #8FB4FF），且**只给主操作和选中态**；
 *   · 层级靠"底色的深浅 + 1dp 描边"，不靠阴影、不靠彩色底；
 *   · 语义色（成功/警告/危险）只在状态点、错误提示上出现，不做装饰。
 *
 * 间距与圆角也一起收敛（见 [UiMetrics]）：4 的倍数、三档圆角。
 * 之前 density / corner 两个设置项能组合出 9 种版式，多数组合都很怪 —— 现在固定。
 */

/**
 * 语义色板。名字沿用历史（下游几十处引用），但取色改成"在深浅两种主题下都能
 * 一眼区分"的一组：不再出现深色主题里 10 个名字映射成 3 个颜色那种事。
 */
internal object AppPalette {
    val blue = Color(0xFF2C6BED)
    val teal = Color(0xFF0E8C7F)
    val indigo = Color(0xFF5A5FD8)
    val purple = Color(0xFF7A5AD8)
    val green = Color(0xFF1E9E5A)
    val orange = Color(0xFFC97A16)
    val red = Color(0xFFD93A34)
    val yellow = Color(0xFFC99A16)
    val pink = Color(0xFFC2417F)
    val mono = Color(0xFF6B7280)

    fun accent(name: String, dark: Boolean): Color = when (name) {
        "blue" -> if (dark) Color(0xFF8FB4FF) else Color(0xFF2C6BED)
        "teal" -> if (dark) Color(0xFF5FD3C0) else Color(0xFF0E8C7F)
        "indigo" -> if (dark) Color(0xFFA9AEFF) else Color(0xFF5A5FD8)
        "purple" -> if (dark) Color(0xFFC7B4E8) else Color(0xFF7A5AD8)
        "green" -> if (dark) Color(0xFF6BD69B) else Color(0xFF1E9E5A)
        "orange" -> if (dark) Color(0xFFF0B267) else Color(0xFFC97A16)
        "red" -> if (dark) Color(0xFFFF9E97) else Color(0xFFD93A34)
        "yellow" -> if (dark) Color(0xFFE8C766) else Color(0xFFC99A16)
        "pink" -> if (dark) Color(0xFFEFA0C4) else Color(0xFFC2417F)
        "mono" -> if (dark) Color(0xFFA3ACBA) else Color(0xFF565E6C)
        else -> if (dark) Color(0xFF8FB4FF) else Color(0xFF2C6BED)
    }
}

/** 形状 token：只保留三档做主用（控件 10 / 卡片 14 / 弹层 20），另留两档给极小与胶囊。 */
internal object AppShape {
    val xs = 6.dp        // 徽标 / 选中条
    val sm = 10.dp       // 控件：按钮 / 输入框 / chip
    val md = 14.dp       // 常规容器：卡片 / 列表组
    val lg = 20.dp       // 大容器：弹层 / 抽屉
    val xl = 24.dp       // 模态
    val pill = 999.dp    // 胶囊
}

/**
 * 文字层级。上一版六档挤在 11/12/14/14/16/22 —— 一屏里几乎全是"小字"，
 * 层级只能靠颜色区分，看着就闷。这一版把档距拉开到 1.2~1.4 倍。
 */
internal object AppText {
    // sp 本身已随系统字体缩放；这里再乘 App 设置里的字号系数。
    // 用 State 承载，切换字号后所有读到 AppText 的界面才会真的重组。
    private var scaleState by mutableStateOf(1f)

    var scale: Float
        get() = scaleState
        set(value) {
            if (value > 0f && value != scaleState) scaleState = value
        }

    private fun scaled(base: Float) = (base * scaleState).sp

    val label get() = scaled(11f)        // 辅助标签 / 说明 / 分组标题
    val body get() = scaled(13f)         // 正文
    val bodyStrong get() = scaled(14f)   // 强调正文 / 列表主文案
    val title get() = scaled(16f)        // 区块标题
    val headline get() = scaled(18f)     // 页面标题
    val display get() = scaled(26f)      // 大数字 / 空态标题
}

/**
 * 版式度量。四个值全部落在 4 的倍数上，并且**不再随 density / corner 设置漂移** ——
 * 之前 9 种组合里有一半是"卡片 24dp 圆角 + 页边距 12dp + 行内边距 8dp"这种怪异搭配。
 * [uiMetrics] 仍接受两个参数，是为了不动几十个调用点。
 */
internal data class UiMetrics(
    val pagePad: androidx.compose.ui.unit.Dp,
    val sectionGap: androidx.compose.ui.unit.Dp,
    val rowPadV: androidx.compose.ui.unit.Dp,
    val cardRadius: androidx.compose.ui.unit.Dp,
    val controlRadius: androidx.compose.ui.unit.Dp,
)

internal fun uiMetrics(@Suppress("UNUSED_PARAMETER") density: String, @Suppress("UNUSED_PARAMETER") corner: String): UiMetrics =
    UiMetrics(
        pagePad = 16.dp,
        sectionGap = 24.dp,
        rowPadV = 14.dp,
        cardRadius = AppShape.md,
        controlRadius = AppShape.sm,
    )

internal fun textScaleFactor(textScale: String): Float = when (textScale) {
    "xsmall" -> 0.9f
    "small" -> 0.95f
    "large" -> 1.08f
    "xlarge" -> 1.15f
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
 * 浅色 —— 干净的纸面：背景极浅冷灰，卡片纯白，靠 1dp 描边分层。
 *
 * 上一版 background 和 surface 都是 #FEFBFF（带紫调），卡片又用 surfaceVariant
 * #E0E2EC（偏蓝灰），三者色相不一致，叠在一起就显得脏。
 */
internal fun appLightColors(accent: Color, highContrast: Boolean) = lightColorScheme(
    primary = accent,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE6FF),
    onPrimaryContainer = Color(0xFF0B2A63),
    secondary = Color(0xFF57606F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE6EAF1),
    onSecondaryContainer = Color(0xFF1A1F28),
    tertiary = Color(0xFF6B5B95),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEAE2F8),
    onTertiaryContainer = Color(0xFF241A38),
    error = Color(0xFFC62828),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFDE0DE),
    onErrorContainer = Color(0xFF4A0B09),
    background = Color(0xFFF6F7F9),
    onBackground = if (highContrast) Color(0xFF000000) else Color(0xFF111419),
    surface = Color(0xFFFFFFFF),
    onSurface = if (highContrast) Color(0xFF000000) else Color(0xFF111419),
    surfaceVariant = Color(0xFFEEF0F4),
    onSurfaceVariant = if (highContrast) Color(0xFF2A2F38) else Color(0xFF565E6C),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFBFC),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF2F4F7),
    surfaceContainerHighest = Color(0xFFE9ECF1),
    outline = if (highContrast) Color(0xFF3F4650) else Color(0xFFC7CDD6),
    outlineVariant = Color(0xFFE3E7ED),
    inverseSurface = Color(0xFF1E232B),
    inverseOnSurface = Color(0xFFF2F4F7),
    inversePrimary = Color(0xFF8FB4FF),
    surfaceTint = accent,
)

/**
 * 深色 —— 石墨。底色 #0B0D11 是不带色相的冷灰，卡片逐级提亮
 * （Low #10131A → Container #141821 → High #191E27 → Highest #1F252F）。
 *
 * pureBlack 仍保留：用户显式要纯黑时把背景压到 #000000，
 * 但卡片仍按石墨档位走，否则纯黑下所有层次都会消失。
 */
internal fun appDarkColors(accent: Color, pureBlack: Boolean, highContrast: Boolean) = darkColorScheme(
    primary = accent,
    onPrimary = Color(0xFF0B2A63),
    primaryContainer = Color(0xFF1C3A70),
    onPrimaryContainer = Color(0xFFD8E4FF),
    secondary = Color(0xFFB6BFCC),
    onSecondary = Color(0xFF1A1F28),
    secondaryContainer = Color(0xFF262D38),
    onSecondaryContainer = Color(0xFFDDE3EC),
    tertiary = Color(0xFFC7B4E8),
    onTertiary = Color(0xFF241A38),
    tertiaryContainer = Color(0xFF35294F),
    onTertiaryContainer = Color(0xFFEADFFA),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF4A0B09),
    errorContainer = Color(0xFF6E1512),
    onErrorContainer = Color(0xFFFFD9D6),
    background = if (pureBlack) Color(0xFF000000) else Color(0xFF0B0D11),
    onBackground = if (highContrast) Color(0xFFFFFFFF) else Color(0xFFE7EAF0),
    surface = if (pureBlack) Color(0xFF000000) else Color(0xFF0B0D11),
    onSurface = if (highContrast) Color(0xFFFFFFFF) else Color(0xFFE7EAF0),
    surfaceVariant = Color(0xFF1A1F27),
    onSurfaceVariant = if (highContrast) Color(0xFFE7EAF0) else Color(0xFFA3ACBA),
    surfaceContainerLowest = Color(0xFF08090C),
    surfaceContainerLow = Color(0xFF10131A),
    surfaceContainer = Color(0xFF141821),
    surfaceContainerHigh = Color(0xFF191E27),
    surfaceContainerHighest = Color(0xFF1F252F),
    outline = if (highContrast) Color(0xFFA3ACBA) else Color(0xFF4B5360),
    outlineVariant = Color(0xFF262C36),
    inverseSurface = Color(0xFFE7EAF0),
    inverseOnSurface = Color(0xFF0B0D11),
    inversePrimary = Color(0xFF2C6BED),
    surfaceTint = accent,
)

internal fun statusSuccess(dark: Boolean = false): Color = if (dark) Color(0xFF4ED17E) else Color(0xFF1E9E5A)
internal fun statusError(dark: Boolean = false): Color = if (dark) Color(0xFFFF8C85) else Color(0xFFD93A34)
internal fun statusWarning(dark: Boolean = false): Color = if (dark) Color(0xFFF0B267) else Color(0xFFC97A16)

/**
 * 历史遗留的 iOS 色板 —— 仍有几处引用（深层聊天、部分面板）。
 * 取值改为跟随新的石墨体系，避免同一屏里同时出现两套灰阶。
 */
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
        val background = Color(0xFFF6F7F9)
        val secondaryBackground = Color(0xFFFFFFFF)
        val tertiaryBackground = Color(0xFFF2F4F7)
        val groupedBackground = Color(0xFFF6F7F9)
        val card = Color(0xFFFFFFFF)
        val fill = Color(0xFFEEF0F4)
        val fillSecondary = Color(0xFFF2F4F7)
        val separator = Color(0xFFE3E7ED)
        val label = Color(0xFF111419)
        val secondaryLabel = Color(0xFF565E6C)
        val tertiaryLabel = Color(0xFF8A919E)
        val primary = AppPalette.blue
        val onPrimary = Color.White
        val success = AppPalette.green
        val error = AppPalette.red
        val warning = AppPalette.orange
        val glass = Color(0xFFFFFFFF)
        val glassStrong = Color(0xFFFFFFFF)
        val glassStroke = Color(0x1A101828)
    }
    object Dark {
        val background = Color(0xFF0B0D11)
        val secondaryBackground = Color(0xFF141821)
        val tertiaryBackground = Color(0xFF1F252F)
        val groupedBackground = Color(0xFF0B0D11)
        val card = Color(0xFF141821)
        val fill = Color(0xFF1F252F)
        val fillSecondary = Color(0xFF191E27)
        val separator = Color(0xFF262C36)
        val label = Color(0xFFE7EAF0)
        val secondaryLabel = Color(0xFFA3ACBA)
        val tertiaryLabel = Color(0xFF737C8A)
        val primary = AppPalette.blue
        val onPrimary = Color.White
        val success = AppPalette.green
        val error = AppPalette.red
        val warning = AppPalette.orange
        val glass = Color(0xFF141821)
        val glassStrong = Color(0xFF191E27)
        val glassStroke = Color(0x1AFFFFFF)
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
