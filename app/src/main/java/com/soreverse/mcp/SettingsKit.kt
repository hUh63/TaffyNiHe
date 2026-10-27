package com.soreverse.mcp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设置界面基座。**所有设置子页共用这一份**，改这里等于改 40+ 个页面。
 *
 * 视觉决定（v1.5.12 重做）：
 *
 *  1. **顶栏收到 56dp**：原来用 `LargeTopAppBar` 的大标题，未滚动时白占约 96dp 高度，
 *     一屏少看两行设置。现在标题 20sp 常驻，返回键 40dp 触控区；
 *  2. **一组就是一张卡**：组内各行共用 `surfaceContainerHigh`，首行上圆角 20dp、
 *     末行下圆角 20dp、中间行圆角 **0** —— 原来中间行留 4dp 圆角，行与行之间会看到
 *     四个小缺口，拼起来不像一张卡而是「一堆小方块」；
 *  3. **行高压到 54/68dp**：原来是 80/96dp 的 iOS 大行高，设置项又多，翻页翻到手酸。
 *     副标题仍然保留（那是信息，不是装饰），只是字号收到 12sp。
 *
 * 图标色：分类入口用 secondaryContainer 瓦片（40dp），普通条目用 primary 单色 22dp。
 * 不把所有行都染成 primary —— 满屏彩色图标会让「哪个是入口、哪个是开关」失去层次。
 */
internal object SettingsDensity {
    val categoryMinHeight = 68.dp
    val rowMinHeight = 54.dp
    val rowHorizontalPadding = 16.dp
    val rowVerticalPadding = 10.dp
    val iconSize = 22.dp
    val tileSize = 40.dp
    val iconGap = 14.dp
    val textGap = 2.dp
    val cardPadding = 14.dp
    val sectionGap = 14.dp
    val groupCorner = 20.dp
    val innerCorner = 0.dp
    val listPadding = 16.dp
}

@Composable
internal fun settingsTitleStyle(): TextStyle =
    MaterialTheme.typography.titleSmall.copy(lineHeight = 20.sp, letterSpacing = 0.sp)

@Composable
internal fun settingsSupportingStyle(): TextStyle =
    MaterialTheme.typography.bodySmall.copy(lineHeight = 17.sp, letterSpacing = 0.sp)

/** 设置页外壳：常驻标题栏 + 懒加载列表（底部留 32dp，键盘弹出时自动让位）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    statusText: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    itemSpacing: androidx.compose.ui.unit.Dp = SettingsDensity.sectionGap,
    content: LazyListScope.() -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        title,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 20.sp,
                        letterSpacing = 0.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (onBack != null) {
                        androidx.compose.material3.IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(
                start = SettingsDensity.listPadding,
                end = SettingsDensity.listPadding,
                top = 4.dp,
                bottom = 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(itemSpacing),
        ) {
            if (!statusText.isNullOrBlank()) {
                item("settings-status") {
                    Text(
                        statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            content()
        }
    }
}

/** 组标题（primary 小标题，压在卡片外面）+ 组内各行。 */
@Composable
internal fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            modifier = Modifier.padding(start = 4.dp),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.3.sp,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}

/**
 * 组内一行的外壳。
 *
 * `first` / `last` 决定上下用外圆角还是直角：一组行的顶行上方圆、底行下方圆，
 * 中间行直角 —— 拼起来是「一张卡」，且不产生嵌套卡片。左右始终是直角，
 * 圆角由组的首末行提供。
 */
@Composable
internal fun PreferenceSurface(
    first: Boolean = true,
    last: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(
            topStart = if (first) SettingsDensity.groupCorner else SettingsDensity.innerCorner,
            topEnd = if (first) SettingsDensity.groupCorner else SettingsDensity.innerCorner,
            bottomStart = if (last) SettingsDensity.groupCorner else SettingsDensity.innerCorner,
            bottomEnd = if (last) SettingsDensity.groupCorner else SettingsDensity.innerCorner,
        ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        onClick = onClick ?: {},
        enabled = onClick != null,
    ) {
        Column(content = content)
    }
}

/** 分类入口行：40dp 图标瓦片 + 标题 + 状态摘要 + 副标题 + chevron。 */
@Composable
internal fun SettingsCategoryRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    value: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = SettingsDensity.categoryMinHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = SettingsDensity.rowHorizontalPadding, vertical = SettingsDensity.rowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SettingsDensity.iconGap),
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Box(Modifier.size(SettingsDensity.tileSize), contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(SettingsDensity.iconSize),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SettingsDensity.textGap)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    title,
                    Modifier.weight(1f),
                    style = settingsTitleStyle(),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (value != null) {
                    Text(
                        value,
                        Modifier.widthIn(max = 104.dp),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                subtitle,
                style = settingsSupportingStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 普通条目行：22dp 单色图标 + 标题 + 副标题 + 可选状态摘要。 */
@Composable
internal fun SettingsItemRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    value: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = SettingsDensity.rowMinHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = SettingsDensity.rowHorizontalPadding, vertical = SettingsDensity.rowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SettingsDensity.iconGap),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(SettingsDensity.iconSize),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SettingsDensity.textGap)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    title,
                    Modifier.weight(1f),
                    style = settingsTitleStyle(),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (value != null) {
                    Text(
                        value,
                        Modifier.widthIn(max = 96.dp),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = settingsSupportingStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 开关行：整行可点（role=Switch），右侧是开关。 */
@Composable
internal fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = SettingsDensity.rowMinHeight)
            .padding(horizontal = SettingsDensity.rowHorizontalPadding, vertical = SettingsDensity.rowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SettingsDensity.iconGap),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(SettingsDensity.iconSize),
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SettingsDensity.textGap)) {
            Text(
                title,
                style = settingsTitleStyle(),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = settingsSupportingStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
