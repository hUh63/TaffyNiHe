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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设置界面基座（对齐太墟 taixu `feature/settings` 的 Personalization 体系）。
 *
 * 三件事决定了它的观感，改动前先读这段：
 *
 *  1. **大标题滚动收起**：`LargeTopAppBar` + `exitUntilCollapsed`，页面上下滚动时
 *     标题从 34sp 收到 22sp 并入 top bar，给内容留出阅读高度；
 *  2. **分组不是一张大卡**：一组的第一行用外圆角（extraLarge）、中间行用小圆角
 *     （extraSmall），视觉上连成一张卡又不做嵌套；组标题在卡片外、primary 色；
 *  3. **行高与图标**：分类入口 96dp + 48dp tonal 图标瓦片（secondaryContainer），
 *     普通条目 80dp + 24dp 单色图标；右侧 value 是「状态摘要」而不是开关。
 */
internal object SettingsDensity {
    val categoryMinHeight = 96.dp
    val rowMinHeight = 80.dp
    val rowHorizontalPadding = 20.dp
    val rowVerticalPadding = 14.dp
    val iconSize = 24.dp
    val tileSize = 48.dp
    val iconGap = 12.dp
    val textGap = 4.dp
    val cardPadding = 16.dp
    val sectionGap = 20.dp
}

@Composable
internal fun settingsTitleStyle(): TextStyle =
    MaterialTheme.typography.titleMedium.copy(lineHeight = 22.sp, letterSpacing = 0.sp)

@Composable
internal fun settingsSupportingStyle(): TextStyle =
    MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp, letterSpacing = 0.sp)

/** 设置页外壳：大标题 + 懒加载列表（底部留 32dp，键盘弹出时自动让位）。 */
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
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(title, fontWeight = FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    if (onBack != null) {
                        androidx.compose.material3.IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
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
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            modifier = Modifier.padding(start = 20.dp, bottom = 12.dp),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}

/**
 * 组内一行的外壳。
 *
 * `first` / `last` 决定四个角用外圆角还是内圆角：一组行的顶行上方圆、底行下方圆，
 * 中间行只留小圆角，拼起来就是「一张卡」但不产生嵌套卡片。
 */
@Composable
internal fun PreferenceSurface(
    first: Boolean = true,
    last: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val outer = MaterialTheme.shapes.extraLarge
    val inner = MaterialTheme.shapes.extraSmall
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(
            topStart = if (first) outer.topStart else inner.topStart,
            topEnd = if (first) outer.topEnd else inner.topEnd,
            bottomStart = if (last) outer.bottomStart else inner.bottomStart,
            bottomEnd = if (last) outer.bottomEnd else inner.bottomEnd,
        ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        onClick = onClick ?: {},
        enabled = onClick != null,
    ) {
        Column(content = content)
    }
}

/** 分类入口行：48dp 图标瓦片 + 标题 + 状态摘要 + 副标题 + chevron。 */
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
        androidx.compose.material3.Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Box(Modifier.size(SettingsDensity.tileSize), contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(SettingsDensity.iconSize + 2.dp),
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
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (value != null) {
                    Text(
                        value,
                        Modifier.widthIn(max = 104.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 普通条目行：24dp 单色图标 + 标题 + 副标题 + 可选状态摘要。 */
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
        Icon(icon, contentDescription = null, modifier = Modifier.size(SettingsDensity.iconSize), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SettingsDensity.textGap)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    title,
                    Modifier.weight(1f),
                    style = settingsTitleStyle(),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (value != null) {
                    Text(
                        value,
                        Modifier.widthIn(max = 96.dp),
                        style = MaterialTheme.typography.labelMedium,
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
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 开关行：整行可点（role=Switch），右侧是带对勾的开关。 */
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
            )
            Text(
                subtitle,
                style = settingsSupportingStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f),
            )
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
