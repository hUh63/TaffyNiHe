package com.soreverse.mcp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 通用页面组件地基 —— **全部基于 Material 3 官方组件**（详见 docs/exbin-ui-spec.md）。
 *
 * 骨架统一为 M3 三件套：`TopAppBar` + 内容 + （需要时）底部栏。
 * 列表统一 `ListItem` / `Card`，禁止自绘 `Box + background + border + clickable`。
 */

/** 页面标题栏 —— M3 [TopAppBar]。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    showBack: Boolean = false,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    titleColor: Color? = null,
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    title,
                    // Exbin 的顶栏就是 MaterialToolbar + TitleMedium(16sp) 常规字重，
                    // 没有「大标题」这一档 —— 之前 24sp 的 headlineSmall 只属于太墟的视觉语言。
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Normal,
                    color = titleColor ?: MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        navigationIcon = {
            if (showBack && onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        "返回",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        actions = { trailing?.invoke() },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

/**
 * 工具页外壳 —— M3 [Scaffold] + [TopAppBar]。
 *
 * 取代「设置子页」范式（大标题 [ScreenHeader] + 页面内边距）——功能页要的是空间。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolPageScaffold(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            title,
                            fontWeight = FontWeight.Normal,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!subtitle.isNullOrBlank()) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                "返回",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
                actions = { actions?.invoke() },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { innerPad ->
        Box(Modifier.fillMaxSize().padding(innerPad)) { content() }
    }
}

/** 分组容器 —— M3 [Card]（Exbin 风：圆角 + 1dp 描边 + 零阴影）。 */
@Composable
internal fun GlassGroup(
    title: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!title.isNullOrBlank()) {
            Text(
                title,
                // 组标题在卡片外、primary 色；start 与卡内正文左对齐（卡内 padding 16，这里 16）。
                // 原来写 20dp 是「标题比正文更靠里」，整页看起来是歪的。
                fontSize = AppText.body,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.3.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            // Exbin `item_detail.xml`：cornerRadius 10dp / elevation 0 / stroke 0 / bg colorSurfaceVariant
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
        if (!footer.isNullOrBlank()) {
            Text(
                footer,
                fontSize = AppText.body,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
internal fun GroupDivider() {
    HorizontalDivider(
        // 缩进与卡片内边距一致（16dp）。不做 iOS 式 56dp 图标缩进：这里有一半的分隔线
        // 用在无图标的输入框/说明文字之间，56dp 会变成一截断头线。
        modifier = Modifier.padding(start = 12.dp),
        thickness = 1.dp,
        // 0.5dp + alpha 0.22 在深色主题下几乎看不见，行与行糊成一片
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** 可点击导航行 —— M3 [ListItem]。 */
@Composable
internal fun NavRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    trailing: String? = null,
    /** 右侧状态摘要（太墟式）：如「:8080」「12 个扩展」，不是按钮。 */
    value: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val resolvedTint = iconTint ?: MaterialTheme.colorScheme.primary
    ListItem(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = icon?.let { { Icon(it, null, tint = resolvedTint, modifier = Modifier.size(22.dp)) } },
        headlineContent = {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = if (!subtitle.isNullOrBlank()) {
            {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else null,
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!value.isNullOrBlank()) {
                    Text(
                        value,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!trailing.isNullOrBlank()) {
                    Text(
                        trailing,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (onClick != null) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
    )
}

/** 带开关的设置行 —— M3 [ListItem] + [Switch]（默认 M3 配色）。 */
@Composable
internal fun ToggleRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
            Text(
                text,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onChange)
        },
    )
}

/** 整宽主操作按钮 —— M3 [Button]。 */
@Composable
internal fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
    container: Color? = null,
) {
    val metrics = LocalUiMetrics.current
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(metrics.controlRadius),
        colors = if (container != null) {
            androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = container,
                contentColor = androidx.compose.material3.contentColorFor(container),
            )
        } else {
            androidx.compose.material3.ButtonDefaults.buttonColors()
        },
        modifier = modifier.height(50.dp),
    ) {
        if (leading != null) {
            Icon(leading, null)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun PageScroll(content: @Composable ColumnScope.() -> Unit) {
    val metrics = LocalUiMetrics.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = metrics.pagePad, vertical = 8.dp)
            .padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(metrics.sectionGap),
        content = content,
    )
}

@Composable
internal fun IndexedBadge(index: Int) {
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Text("${index + 1}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    }
}

/** 承载任意内容区 —— M3 [Card]（同 [GlassGroup]，可选内边距）。 */
@Composable
internal fun AppCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    footer: String? = null,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val metrics = LocalUiMetrics.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!title.isNullOrBlank()) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
            )
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(metrics.cardRadius),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.fillMaxWidth().padding(contentPadding), content = content)
        }
        if (!footer.isNullOrBlank()) {
            Text(footer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 2.dp))
        }
    }
}

/** 列表项 —— M3 [ListItem]（对齐 Explorer So item_list_card 的信息层级）。 */
@Composable
internal fun CardRow(
    title: String,
    subtitle: String? = null,
    meta: String? = null,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val tint = iconTint ?: cs.primary
    ListItem(
        modifier = Modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = icon?.let { { Icon(it, null, tint = tint, modifier = Modifier.size(24.dp)) } },
        headlineContent = {
            Text(title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = if (!subtitle.isNullOrBlank() || !meta.isNullOrBlank()) {
            {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (!subtitle.isNullOrBlank()) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (!meta.isNullOrBlank()) {
                        Text(meta, style = MaterialTheme.typography.labelSmall, color = cs.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        } else null,
        trailingContent = trailing,
    )
}
