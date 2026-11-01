package com.soreverse.mcp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private data class NavItem(val tab: MainTab, val labelZh: String, val labelEn: String, val icon: ImageVector)

private val navItems = listOf(
    NavItem(MainTab.Home, "首页", "Home", Icons.Filled.Home),
    NavItem(MainTab.Tools, "分析", "Analyze", Icons.Filled.Science),
    NavItem(MainTab.Tasks, "任务", "Tasks", Icons.Filled.Folder),
    NavItem(MainTab.Editor, "编辑器", "Editor", Icons.Filled.Code),
    NavItem(MainTab.Settings, "设置", "Settings", Icons.Filled.Settings),
)

/**
 * 底部导航。
 *
 * 三个改动：
 * 1. **收高度**。M3 默认 80dp + 32dp 指示器，在 6 寸屏上占了将近 1/8 屏高，
 *    而它承载的信息只有 5 个词。压到 64dp。
 * 2. **顶部加一条 1dp 分隔线**。原来只有底色差，深色主题下导航栏和内容区
 *    几乎同色，滚到底会分不清哪是内容哪是导航。
 * 3. **选中态只给图标上色**，文字保持次级色 —— 5 个 tab 全部主色文字会抢走
 *    主操作的注意力。
 */
@Composable
internal fun AppBottomNav(current: MainTab, zh: Boolean, onSelect: (MainTab) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        NavigationBar(
            modifier = Modifier.fillMaxWidth().height(64.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            windowInsets = WindowInsets(0, 0, 0, 0),
        ) {
            navItems.forEach { item ->
                NavigationBarItem(
                    selected = current == item.tab,
                    onClick = { onSelect(item.tab) },
                    icon = { Icon(item.icon, contentDescription = null, modifier = Modifier.size(22.dp)) },
                    label = {
                        Text(
                            if (zh) item.labelZh else item.labelEn,
                            fontSize = AppText.label,
                            fontWeight = if (current == item.tab) FontWeight.Medium else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    alwaysShowLabel = true,
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        }
    }
}
