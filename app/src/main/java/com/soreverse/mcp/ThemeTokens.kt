package com.soreverse.mcp

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
    val pink = Color(0xFFD6336C)
    val mono = Color(0xFF6B7280)

    /**
     * 当文字/图标用的强调色：浅色下压暗到 ≥4.5:1（深色下原样）。
     * 直接拿 accent() 上色做文字，浅色主题里的橙/绿/黄只有 2.4~3.9:1。
     */
    fun ink(name: String, dark: Boolean): Color =
        if (dark) accent(name, dark) else inkOf(accent(name, dark))

    fun accent(name: String, dark: Boolean): Color = when (name) {
        "blue" -> if (dark) Color(0xFF8FB4FF) else Color(0xFF2C6BED)
        "teal" -> if (dark) Color(0xFF5FD3C0) else Color(0xFF0E8C7F)
        "indigo" -> if (dark) Color(0xFFA9AEFF) else Color(0xFF5A5FD8)
        "purple" -> if (dark) Color(0xFFC7B4E8) else Color(0xFF7A5AD8)
        "green" -> if (dark) Color(0xFF6BD69B) else Color(0xFF1E9E5A)
        "orange" -> if (dark) Color(0xFFF0B267) else Color(0xFFC97A16)
        "red" -> if (dark) Color(0xFFFF9E97) else Color(0xFFD93A34)
        "yellow" -> if (dark) Color(0xFFE8C766) else Color(0xFFC99A16)
        "pink" -> if (dark) Color(0xFFFF8FBC) else Color(0xFFD6336C)
        "mono" -> if (dark) Color(0xFFA3ACBA) else Color(0xFF565E6C)
        // 默认就是粉：设置里 accentColor 的默认值也是 "pink"
        else -> if (dark) Color(0xFFFF8FBC) else Color(0xFFD6336C)
    }
}


/**
 * 一个强调色的**完整**派生色板。
 *
 * 上一版 `appLightColors(accent)` 只把传入色塞进 primary，其余槽位（primaryContainer、
 * secondary、tertiary、surfaceVariant）全是写死的蓝紫灰 —— 选粉色之后只有按钮变粉，
 * 卡片、chip、选中底、容器描边还是蓝的，看起来像"没换主题"。
 *
 * 这里以强调色的**色相**为基准用 HSL 派生整套：
 *   · 容器 container      ＝ 同色相、高亮、降饱和（选中底 / 主色卡片）
 *   · 邻近 analogous      ＝ 色相 −30°，进 secondaryContainer（次级容器、进度底）
 *   · 互补 complement     ＝ 色相 +180°，进 tertiary（对比强调、图表第二色）
 *   · 表面 tintedSurface  ＝ 表面色微带主色相（3~8% 饱和），让整页底色"跟着走"
 *   · 图表 chart          ＝ 主色 / 互补 / 三角 120°·240° / 邻近 / 冷灰
 *
 * 文字槽（secondary / onSecondary / onSurfaceVariant）仍保持近乎中性，只带一点色相 ——
 * 否则"次要文字"会变成粉字，满屏像链接。
 */
internal data class AccentPalette(
    /** 强调色名（blue/pink/…），给需要按名字分支的地方用。 */
    val name: String,
    val accent: Color,
    val onAccent: Color,
    /**
     * 当"文字色"用的强调色。浅色下 primary 会被压暗到相对亮度 ≤0.18 ——
     * 否则橙/绿/青/黄当文字放在浅底上只有 2.4~3.9:1（读不清）。
     * 深色下与 accent 相同（亮色放在深底上本来就够）。
     */
    val ink: Color,
    val onInk: Color,
    /** 主色容器：选中项底、主 CTA 的浅底、状态胶囊。 */
    val container: Color,
    val onContainer: Color,
    /** 邻近色：进 secondaryContainer。 */
    val analogous: Color,
    val analogousContainer: Color,
    val onAnalogousContainer: Color,
    /** 互补色：进 tertiary / tertiaryContainer。 */
    val complement: Color,
    /** 互补色上的文字色（与 onAccent 同规则）。 */
    val onTertiary: Color,
    val complementContainer: Color,
    val onComplementContainer: Color,
    /** 极浅/极暗的主色底，给状态胶囊、图表高亮区用。 */
    val soft: Color,
    /** 主色描边（1dp 线）—— 需要"轻强调"而不是实心块时用。 */
    val line: Color,
    /** 表面色微带主色相（surfaceVariant / surfaceContainerHigh）。 */
    val tintedSurface: Color,
    val tintedSurfaceHigh: Color,
    /**
     * 页面底与三层容器也跟着掺色相（背景 / Lowest / Low / Container）。
     * 亮度与原来的中性灰档位逐一持平 —— 只换色相，层次与对比度一点不动。
     */
    val tintedBackground: Color,
    val tintedLowest: Color,
    val tintedLow: Color,
    val tintedBase: Color,
    /** 1dp 描边：带一点主色相，整页的线才跟卡片、容器是同一套色。 */
    val outline: Color,
    val outlineVariant: Color,
    /** 图表/多分类色序列（6 个，已按深浅调过亮度）。 */
    val chart: List<Color>,
)

/** HSL → Color。s/l 取值 0..1，h 任意角度。 */
private fun hslColor(h: Float, s: Float, l: Float): Color {
    val hh = ((h % 360f) + 360f) % 360f
    val ss = s.coerceIn(0f, 1f)
    val ll = l.coerceIn(0f, 1f)
    val c = (1f - kotlin.math.abs(2f * ll - 1f)) * ss
    val x = c * (1f - kotlin.math.abs((hh / 60f) % 2f - 1f))
    val m = ll - c / 2f
    val r: Float
    val g: Float
    val b: Float
    when {
        hh < 60f -> { r = c; g = x; b = 0f }
        hh < 120f -> { r = x; g = c; b = 0f }
        hh < 180f -> { r = 0f; g = c; b = x }
        hh < 240f -> { r = 0f; g = x; b = c }
        hh < 300f -> { r = x; g = 0f; b = c }
        else -> { r = c; g = 0f; b = x }
    }
    return Color(r + m, g + m, b + m)
}

/** Color → (h, s, l)。 */
private fun Color.toHsl(): FloatArray {
    val r = red
    val g = green
    val b = blue
    val max = kotlin.math.max(r, kotlin.math.max(g, b))
    val min = kotlin.math.min(r, kotlin.math.min(g, b))
    val l = (max + min) / 2f
    if (max == min) return floatArrayOf(0f, 0f, l)
    val d = max - min
    val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
    val h = when (max) {
        r -> ((g - b) / d + if (g < b) 6f else 0f)
        g -> ((b - r) / d + 2f)
        else -> ((r - g) / d + 4f)
    } * 60f
    return floatArrayOf(h, s, l)
}

/**
 * 把颜色压暗到「能当文字/图标用」的亮度（默认 ≤0.15，即对最亮的卡片面 ≥4.5:1）。
 * 阈值按**最低**的浅色表面反算：surfaceVariant（l=0.945）比页底和 surfaceContainerHigh
 * 都暗，按页底算会漏掉 chip 底上的字（实测差 0.05~0.15）。
 * 只动 HSL 的 lightness、保留色相与饱和度，所以压暗后还是"用户选的那个颜色"，
 * 只是深了一档 —— 与 Material 3 动态取色把浅色 primary 定在 tone 40 是同一个道理。
 */
private fun inkOf(c: Color, maxLum: Float = 0.15f): Color {
    if (c.luminance() <= maxLum) return c
    val hsl = c.toHsl()
    var l = hsl[2]
    var out = c
    var guard = 0
    while (guard < 60 && out.luminance() > maxLum && l > 0.05f) {
        l -= 0.01f
        out = hslColor(hsl[0], hsl[1], l)
        guard++
    }
    return out
}

/**
 * 由强调色派生整套色板。[name] 只用于标记来源，不影响取色逻辑 ——
 * 即使开了 Material You（系统色），也能从传入的任意颜色派生出一致的配色。
 */
internal fun accentPaletteOf(accent: Color, dark: Boolean, name: String = ""): AccentPalette {
    val hsl = accent.toHsl()
    val h = hsl[0]
    val s = hsl[1]
    val l = hsl[2]
    fun tone(hh: Float, ss: Float, ll: Float) = hslColor(hh, ss, ll)

    // 用相对亮度判白字还是深色字，临界点 L=0.194：
    // 白字对比 = 1.05/(L+0.05)，深字（#0A0D11，L≈0.0046）对比 = (L+0.05)/0.0546，
    // 两者相等时 L≈0.194 —— 低于它白字更高、高于它深字更高。
    // v1.5.40 用的 0.179 偏低：红色 #D93A34（L=0.180）被判成深字，只有 4.05:1，
    // 而白字其实是 4.56:1 已过 AA。
    fun onColorOf(c: Color) = if (c.luminance() > 0.194f) Color(0xFF0A0D11) else Color(0xFFFFFFFF)
    val onAccent = onColorOf(accent)
    // 浅色下 primary 要当文字/图标用，必须压到对纯白 ≥4.5:1
    val ink = if (dark) accent else inkOf(accent)
    val onInk = onColorOf(ink)
    val container = if (dark) tone(h, s * 0.60f, 0.245f) else tone(h, kotlin.math.min(s, 0.80f), 0.930f)
    val onContainer = if (dark) tone(h, 0.70f, 0.900f) else tone(h, 0.62f, 0.185f)

    val analogousContainer = if (dark) tone(h - 30f, 0.28f, 0.225f) else tone(h - 30f, 0.46f, 0.945f)
    val onAnalogousContainer = if (dark) tone(h - 30f, 0.58f, 0.900f) else tone(h - 30f, 0.55f, 0.185f)

    val complementRaw = if (dark) tone(h + 180f, 0.60f, 0.740f) else tone(h + 180f, 0.56f, 0.380f)
    // 浅色下 tertiary 也当文字/图标用（对比按钮、图例），l=0.38 的黄绿只有 3.0:1，要压暗
    val complement = if (dark) complementRaw else inkOf(complementRaw)
    val complementContainer = if (dark) tone(h + 180f, 0.38f, 0.225f) else tone(h + 180f, 0.50f, 0.920f)
    val onComplementContainer = if (dark) tone(h + 180f, 0.52f, 0.900f) else tone(h + 180f, 0.58f, 0.180f)

    // 近乎中性的"次强调"：只带一点主色相，正文/次要文字用它才不会变粉字
    val analogousRaw = if (dark) tone(h, 0.12f, 0.780f) else tone(h, 0.14f, 0.395f)
    val analogous = if (dark) analogousRaw else inkOf(analogousRaw)

    val soft = if (dark) tone(h, s * 0.52f, 0.200f) else tone(h, kotlin.math.min(s, 0.66f), 0.955f)
    val line = if (dark) tone(h, s * 0.48f, 0.420f) else tone(h, kotlin.math.min(s, 0.55f), 0.745f)

    // 表面微 tint：亮度必须保持原石墨档位（深色 0.155 → 浅色 0.945），只把色相掺进去
    val tintedSurface = if (dark) tone(h, 0.10f, 0.155f) else tone(h, 0.22f, 0.945f)
    val tintedSurfaceHigh = if (dark) tone(h, 0.10f, 0.128f) else tone(h, 0.20f, 0.965f)
    // 页面底与上层容器：亮度沿用原中性格（深 0.055 / 0.039 / 0.082 / 0.104，浅 0.971 / 1.0 / 0.984 / 1.0）。
    // 浅色的 Lowest 与 Container 本来就是纯白（l=1.0，s 再高也还是白），所以浅色实际只动了背景和 Low。
    val tintedBackground = if (dark) tone(h, 0.10f, 0.055f) else tone(h, 0.18f, 0.971f)
    val tintedLowest = if (dark) tone(h, 0.10f, 0.039f) else tone(h, 0f, 1f)
    val tintedLow = if (dark) tone(h, 0.10f, 0.082f) else tone(h, 0.16f, 0.984f)
    val tintedBase = if (dark) tone(h, 0.10f, 0.104f) else tone(h, 0f, 1f)
    // 亮度与原灰阶持平（浅 0.80 / 深 0.335），只把色相掺进来；对比度不变，观感统一
    val outline = if (dark) tone(h, 0.12f, 0.335f) else tone(h, 0.13f, 0.800f)
    // 浅色 outlineVariant 压到 0.884：surfaceVariant（chip 底，l=0.945）是最暗的浅色表面，
    // 0.910 时对比只有 1.08:1，1dp 分割线在 chip 里等于没有；0.884 有 1.16:1
    val outlineVariant = if (dark) tone(h, 0.10f, 0.200f) else tone(h, 0.12f, 0.884f)

    val chart = listOf(
        accent,
        complementRaw,
        if (dark) tone(h + 120f, 0.52f, 0.700f) else tone(h + 120f, 0.50f, 0.400f),
        if (dark) tone(h + 240f, 0.52f, 0.720f) else tone(h + 240f, 0.48f, 0.420f),
        if (dark) tone(h - 30f, 0.52f, 0.720f) else tone(h - 30f, 0.46f, 0.440f),
        if (dark) tone(h, 0.10f, 0.620f) else tone(h, 0.10f, 0.500f),
    )

    return AccentPalette(
        name = name,
        accent = accent,
        onAccent = onAccent,
        ink = ink,
        onInk = onInk,
        container = container,
        onContainer = onContainer,
        analogous = analogous,
        analogousContainer = analogousContainer,
        onAnalogousContainer = onAnalogousContainer,
        complement = complement,
        onTertiary = onColorOf(complement),
        complementContainer = complementContainer,
        onComplementContainer = onComplementContainer,
        soft = soft,
        line = line,
        tintedSurface = tintedSurface,
        tintedSurfaceHigh = tintedSurfaceHigh,
        tintedBackground = tintedBackground,
        tintedLowest = tintedLowest,
        tintedLow = tintedLow,
        tintedBase = tintedBase,
        outline = outline,
        outlineVariant = outlineVariant,
        chart = chart,
    )
}

/** 当前生效的强调色板。页面要"跟着强调色变"的地方（图表、胶囊、图标底）读它。 */
internal val LocalAccentPalette = staticCompositionLocalOf { accentPaletteOf(AppPalette.pink, false, "pink") }

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
internal fun appLightColors(accent: Color, highContrast: Boolean, accentName: String = ""): ColorScheme {
    // 整套槽位都从强调色派生：换色之后卡片底、chip、选中底、描边一起变，而不是只有按钮变
    val p = accentPaletteOf(accent, dark = false, name = accentName)
    return lightColorScheme(
        // 浅色底上的主色要当文字/图标用，所以取压暗后的 ink（对比 ≥4.5）；
        // 原色 p.accent 仍给图表/填充块/预览色块用
        primary = p.ink,
        onPrimary = p.onInk,
        primaryContainer = p.container,
        onPrimaryContainer = p.onContainer,
        // secondary 只带一点主色相（近乎中性），避免"次要文字变粉字"
        secondary = p.analogous,
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = p.analogousContainer,
        onSecondaryContainer = p.onAnalogousContainer,
        // tertiary 用互补色：对比强调（徽标、图表第二色、对比按钮）
        tertiary = p.complement,
        onTertiary = p.onTertiary,
        tertiaryContainer = p.complementContainer,
        onTertiaryContainer = p.onComplementContainer,
        error = Color(0xFFC62828),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFDE0DE),
        onErrorContainer = Color(0xFF4A0B09),
        background = p.tintedBackground,
        onBackground = if (highContrast) Color(0xFF000000) else Color(0xFF111419),
        surface = p.tintedLowest,
        onSurface = if (highContrast) Color(0xFF000000) else Color(0xFF111419),
        surfaceVariant = p.tintedSurface,
        onSurfaceVariant = if (highContrast) Color(0xFF2A2F38) else Color(0xFF565E6C),
        surfaceContainerLowest = p.tintedLowest,
        surfaceContainerLow = p.tintedLow,
        surfaceContainer = p.tintedBase,
        surfaceContainerHigh = p.tintedSurfaceHigh,
        surfaceContainerHighest = p.tintedSurface,
        outline = if (highContrast) Color(0xFF3F4650) else p.outline,
        outlineVariant = p.outlineVariant,
        inverseSurface = Color(0xFF1E232B),
        inverseOnSurface = Color(0xFFF2F4F7),
        inversePrimary = accentPaletteOf(accent, dark = true, name = accentName).accent,
        surfaceTint = p.accent,
    )
}

/**
 * 深色 —— 石墨。底色与卡片逐级提亮（背景 → Lowest → Low → Container → High → Highest），
 * 每一档的亮度固定不变，只把强调色的色相掺进来（v1.5.41 起），所以整套界面是
 * 「一片同色系的深底上浮起几层」，而不是中性格里嵌着几张彩色卡片。
 *
 * pureBlack 仍保留：用户显式要纯黑时把背景压到 #000000，
 * 但卡片仍按石墨档位走，否则纯黑下所有层次都会消失。
 */
internal fun appDarkColors(accent: Color, pureBlack: Boolean, highContrast: Boolean, accentName: String = ""): ColorScheme {
    val p = accentPaletteOf(accent, dark = true, name = accentName)
    return darkColorScheme(
        primary = p.accent,
        onPrimary = p.onAccent,
        primaryContainer = p.container,
        onPrimaryContainer = p.onContainer,
        secondary = p.analogous,
        onSecondary = Color(0xFF14181F),
        secondaryContainer = p.analogousContainer,
        onSecondaryContainer = p.onAnalogousContainer,
        tertiary = p.complement,
        onTertiary = p.onTertiary,
        tertiaryContainer = p.complementContainer,
        onTertiaryContainer = p.onComplementContainer,
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF4A0B09),
        errorContainer = Color(0xFF6E1512),
        onErrorContainer = Color(0xFFFFD9D6),
        background = if (pureBlack) Color(0xFF000000) else p.tintedBackground,
        onBackground = if (highContrast) Color(0xFFFFFFFF) else Color(0xFFE7EAF0),
        surface = if (pureBlack) Color(0xFF000000) else p.tintedBackground,
        onSurface = if (highContrast) Color(0xFFFFFFFF) else Color(0xFFE7EAF0),
        surfaceVariant = p.tintedSurface,
        onSurfaceVariant = if (highContrast) Color(0xFFE7EAF0) else Color(0xFFA3ACBA),
        surfaceContainerLowest = p.tintedLowest,
        surfaceContainerLow = p.tintedLow,
        surfaceContainer = p.tintedBase,
        surfaceContainerHigh = p.tintedSurfaceHigh,
        surfaceContainerHighest = p.tintedSurface,
        outline = if (highContrast) Color(0xFFA3ACBA) else p.outline,
        outlineVariant = p.outlineVariant,
        inverseSurface = Color(0xFFE7EAF0),
        inverseOnSurface = Color(0xFF0B0D11),
        inversePrimary = accentPaletteOf(accent, dark = false, name = accentName).accent,
        surfaceTint = p.accent,
    )
}

/**
 * 语义状态色。浅色下这三个值会被压暗到对浅底 ≥4.5:1 ——
 * 它们几乎总是当**文字/图标**用（"成功""失败"、状态点、日志级别），
 * 原来的 #1E9E5A / #C97A16 在浅色底上只有 3.2:1，小字号看不清。
 */
internal fun statusSuccess(dark: Boolean = false): Color =
    if (dark) Color(0xFF4ED17E) else inkOf(Color(0xFF1E9E5A))

internal fun statusError(dark: Boolean = false): Color =
    if (dark) Color(0xFFFF8C85) else inkOf(Color(0xFFD93A34))

internal fun statusWarning(dark: Boolean = false): Color =
    if (dark) Color(0xFFF0B267) else inkOf(Color(0xFFC97A16))

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

internal fun appleLightColors() = appLightColors(AppPalette.pink, false, "pink")
internal fun appleDarkColors() = appDarkColors(AppPalette.pink, true, false, "pink")
internal fun appleSuccess(dark: Boolean = false): Color = statusSuccess(dark)
internal fun appleError(dark: Boolean = false): Color = statusError(dark)
internal fun appleWarning(dark: Boolean = false): Color = statusWarning(dark)

internal val LocalUiMetrics = staticCompositionLocalOf {
    uiMetrics("comfortable", "medium")
}
internal val LocalReduceMotion = staticCompositionLocalOf { false }
