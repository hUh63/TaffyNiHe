package com.soreverse.mcp

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun SettingsAppearancePage(
    t: UiText,
    language: String,
    onLanguage: (String) -> Unit,
    themeMode: String,
    onTheme: (String) -> Unit,
    accentColor: String,
    onAccent: (String) -> Unit,
    pureBlackDark: Boolean,
    onPureBlack: (Boolean) -> Unit,
    uiDensity: String,
    onDensity: (String) -> Unit,
    cornerStyle: String,
    onCorner: (String) -> Unit,
    motionMode: String,
    onMotion: (String) -> Unit,
    showAdvancedHome: Boolean,
    onShowAdvancedHome: (Boolean) -> Unit,
    highContrast: Boolean,
    onHighContrast: (Boolean) -> Unit,
    dynamicColor: Boolean,
    onDynamicColor: (Boolean) -> Unit,
    textScale: String,
    onTextScale: (String) -> Unit,
    predictiveBack: Boolean,
    onPredictiveBack: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    SettingsScaffold(
        title = if (t.zh) "外观与语言" else "Appearance",
        onBack = onBack,
    ) {
        item("content") {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
        SurfacePanel {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(if (t.zh) "实时预览" else "Live preview", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                        .border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                }
                Text(if (t.zh) "强调色会立刻影响按钮与选中态" else "Accent updates buttons and selection immediately", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PrimaryActionButton(if (t.zh) "示例主按钮" else "Sample primary", onClick = {}, modifier = Modifier.fillMaxWidth())
            }
        }
        GlassGroup(title = if (t.zh) "预设" else "Presets") {
            PrimaryActionButton(
                if (t.zh) "IDA 深色 · 等宽紧凑" else "IDA Dark · mono compact",
                onClick = {
                    onTheme("dark")
                    onAccent("mono")
                    onPureBlack(true)
                    onDensity("compact")
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // 语言 / 主题 / 强调色 / 密度 / 圆角 / 字号 / 动效 原来是七张单行卡——
        // 每一张卡只有一行 chip，整页像七个标签贴纸。都是「选一个值」的同构控件，
        // 按「外观（颜色与语言）」和「布局与文字（尺寸与动效）」合成两张卡。
        GlassGroup(title = if (t.zh) "外观" else "Appearance") {
            ChipFieldLabel(t.language)
            ChipRow(listOf("system" to if (t.zh) "跟随系统" else "System", "zh" to "中文", "en" to "English"), language, onLanguage)
            GroupDivider()
            ChipFieldLabel(t.theme)
            ChipRow(listOf("system" to if (t.zh) "跟随系统" else "System", "light" to if (t.zh) "浅色" else "Light", "dark" to if (t.zh) "深色" else "Dark"), themeMode, onTheme)
            GroupDivider()
            ChipFieldLabel(if (t.zh) "强调色" else "Accent")
            if (dynamicColor) {
                Text(
                    if (t.zh) "已开启动态取色（Material You）：实际配色由壁纸决定，下面的选择暂时不生效"
                    else "Dynamic color is on — the palette follows your wallpaper, so the choice below has no effect yet",
                    fontSize = AppText.label,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                )
            }
            // 标出色块：只有文字的话，用户点完不知道"粉"长什么样。
            // 粉放在第一位（也是默认值）；深色下用 #FF8FBC，与紫能区分开。
            AccentChipRow(
                listOf(
                    Triple("pink", if (t.zh) "粉" else "Pink", AppPalette.pink),
                    Triple("blue", if (t.zh) "蓝" else "Blue", AppPalette.blue),
                    Triple("teal", if (t.zh) "青" else "Teal", AppPalette.teal),
                    Triple("indigo", if (t.zh) "靛" else "Indigo", AppPalette.indigo),
                    Triple("purple", if (t.zh) "紫" else "Purple", AppPalette.purple),
                    Triple("green", if (t.zh) "绿" else "Green", AppPalette.green),
                    Triple("orange", if (t.zh) "橙" else "Orange", AppPalette.orange),
                ),
                accentColor,
                onAccent,
            )
            AccentPreviewRow(accentColor, t.zh)
        }
        GlassGroup(title = if (t.zh) "文字" else "Text") {
            // 版式（页边距 / 行高 / 圆角）已经固定成一套 4 的倍数 + 三档圆角，
            // 再留「密度 / 圆角」两个旋钮只会让用户调出更怪的组合，所以整组去掉了。
            ChipFieldLabel(if (t.zh) "字号" else "Text size")
            ChipRow(
                listOf(
                    "xsmall" to if (t.zh) "特小" else "XS",
                    "small" to if (t.zh) "小" else "Small",
                    "normal" to if (t.zh) "标准" else "Normal",
                    "large" to if (t.zh) "大" else "Large",
                    "xlarge" to if (t.zh) "特大" else "XL",
                ),
                textScale,
                onTextScale,
            )
            GroupDivider()
            ChipFieldLabel(if (t.zh) "动效" else "Motion")
            ChipRow(
                listOf(
                    "system" to if (t.zh) "跟随系统" else "System",
                    "full" to if (t.zh) "标准" else "Full",
                    "reduced" to if (t.zh) "减弱" else "Reduced",
                ),
                motionMode,
                onMotion,
            )
        }
        GlassGroup {
            ToggleRow(if (t.zh) "纯黑深色背景" else "Pure black dark mode", pureBlackDark, onPureBlack)
            GroupDivider()
            ToggleRow(if (t.zh) "动态取色（Material You，Android 12+）" else "Dynamic color (Material You, Android 12+)", dynamicColor, onDynamicColor)
            GroupDivider()
            ToggleRow(if (t.zh) "高对比" else "High contrast", highContrast, onHighContrast)
            GroupDivider()
            ToggleRow(t.predictiveBack, predictiveBack, onPredictiveBack)
        }
    
            }
        }
    }

}

/** 分组卡内部的字段小标题：与 [ChipRow] 的 14dp 横向内边距对齐，比组标题低一层。 */
@Composable
private fun ChipFieldLabel(text: String) {
    Text(
        text,
        fontSize = AppText.body,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp),
    )
}

/** 带色块的强调色选择器：文字 + 实际颜色圆点，选中用该色自己的容器底。 */
@Composable
private fun AccentChipRow(
    items: List<Triple<String, String, Color>>,
    selected: String,
    onSelected: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        val dark = isDarkColor(cs.background)
        items.forEach { (value, label, _) ->
            val isSel = selected == value
            // 用当前主题下的强调色（深色主题用亮版，色点才不会在深底上糊成一团）；
            // 选中项的底用"该强调色派生出来的容器色"，所以选粉是浅粉底、选青是浅青底
            val swatch = AppPalette.accent(value, dark)
            val pal = accentPaletteOf(swatch, dark, value)
            FilterChip(
                selected = isSel,
                onClick = { onSelected(value) },
                label = { Text(label) },
                leadingIcon = {
                    Box(
                        Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(swatch)
                            .border(1.dp, cs.outline.copy(alpha = 0.30f), CircleShape),
                    )
                },
                shape = RoundedCornerShape(AppShape.pill),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = pal.container,
                    selectedLabelColor = pal.onContainer,
                    selectedLeadingIconColor = pal.onContainer,
                    containerColor = cs.surfaceVariant.copy(alpha = 0.55f),
                    labelColor = cs.onSurface,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = isSel,
                    borderColor = cs.outline.copy(alpha = 0.28f),
                    selectedBorderColor = pal.line,
                ),
            )
        }
    }
}

/** 当前主题是深色还是浅色（用表面亮度判断，避免再往这里传一个参数）。 */
private fun isDarkColor(c: Color): Boolean = c.luminance() < 0.5f

/**
 * 强调色下方的"整套配色"预览：六个色块就是 [AccentPalette.chart]，
 * 让用户选色的时候就能看到容器/互补/三角色长什么样，而不是选完回首页看。
 */
@Composable
private fun AccentPreviewRow(accentName: String, zh: Boolean) {
    val cs = MaterialTheme.colorScheme
    val dark = isDarkColor(cs.background)
    val pal = accentPaletteOf(AppPalette.accent(accentName, dark), dark, accentName)
    Column(Modifier.padding(horizontal = 14.dp).padding(bottom = 10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            pal.chart.forEach { c ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(AppShape.xs))
                        .background(c),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (zh) "主色 · 互补 · 三角 120° / 240° · 邻近 · 中性 —— 这组色会同时用在容器、页签、图表和首页入口图标上"
            else "accent · complement · triadic 120°/240° · analogous · neutral — used across containers, tabs, charts and launcher icons",
            fontSize = AppText.label,
            color = cs.onSurfaceVariant,
        )
    }
}
