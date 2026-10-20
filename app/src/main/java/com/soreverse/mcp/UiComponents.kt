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
                    // 太墟式：无副标题的设置子页用大标题（24sp 常规字重），标题本身就是视觉锚点
                    style = if (subtitle.isNullOrBlank()) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
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
                            fontWeight = FontWeight.SemiBold,
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
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
    val metrics = LocalUiMetrics.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!title.isNullOrBlank()) {
            Text(
                title,
                // 太墟式：组标题在卡片外、primary 色（不是卡内灰标题）
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
            )
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
        if (!footer.isNullOrBlank()) {
            Text(
                footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

@Composable
internal fun GroupDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
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
            .heightIn(min = 64.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = icon?.let { { Icon(it, null, tint = resolvedTint, modifier = Modifier.size(24.dp)) } },
        headlineContent = {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
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

/**
 * 设置子页当前滚动位置（像素）。
 *
 * 只为了让 [SettingsSubPageHeader] 把大标题随滚动收成小标题——太墟（taixu）的二级页
 * 是「34sp 大标题 → 滚动后 22sp」，但要折叠就得知道页面滚到哪；页面各自持有
 * [rememberScrollState]，所以用 CompositionLocal 把它递出来，而不是让每个页面
 * 都改成 LazyColumn 架构。
 */
internal val LocalPageScrollState = compositionLocalOf<ScrollState?> { null }

@Composable
internal fun PageScroll(content: @Composable ColumnScope.() -> Unit) {
    val metrics = LocalUiMetrics.current
    val scroll = rememberScrollState()
    CompositionLocalProvider(LocalPageScrollState provides scroll) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = metrics.pagePad, vertical = 8.dp)
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(metrics.sectionGap),
            content = content,
        )
    }
}

/**
 * 设置子页顶栏 —— 太墟式「折叠大标题」。
 *
 * 标题字号与行高随 [LocalPageScrollState] 的滚动偏移在 34sp→22sp 之间线性收缩，
 * 顶栏高度同步 96dp→64dp。没有滚动源的页面（少数自绘 Column）保持大标题不折叠。
 *
 * 只在本 composable 内读 scroll.value：读在外层会让整页每帧重组。
 */
@Composable
internal fun SettingsSubPageHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val scroll = LocalPageScrollState.current
    val progress = ((scroll?.value ?: 0) / 96f).coerceIn(0f, 1f)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = (64f - 24f * progress).dp)
            .padding(start = 4.dp, end = 12.dp, top = (10f - 6f * progress).dp, bottom = (6f - 4f * progress).dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = MaterialTheme.colorScheme.onSurface)
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Text(
            title,
            modifier = Modifier.weight(1f).padding(start = 4.dp, bottom = (6f - 4f * progress).dp),
            fontSize = (34f - 12f * progress).sp,
            lineHeight = (40f - 12f * progress).sp,
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing?.invoke()
    }
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
