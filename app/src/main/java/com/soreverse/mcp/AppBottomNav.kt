package com.soreverse.mcp

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

private data class NavItem(val tab: MainTab, val labelZh: String, val labelEn: String, val icon: ImageVector)

private val navItems = listOf(
    NavItem(MainTab.Home, "首页", "Home", Icons.Filled.Home),
    NavItem(MainTab.Tools, "分析", "Analyze", Icons.Filled.Science),
    NavItem(MainTab.Tasks, "任务", "Tasks", Icons.Filled.Folder),
    NavItem(MainTab.Settings, "设置", "Settings", Icons.Filled.Settings),
)

@Composable
internal fun AppBottomNav(current: MainTab, zh: Boolean, onSelect: (MainTab) -> Unit) {
    NavigationBar(modifier = Modifier.fillMaxWidth()) {
        navItems.forEach { item ->
            NavigationBarItem(
                selected = current == item.tab,
                onClick = { onSelect(item.tab) },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(if (zh) item.labelZh else item.labelEn) },
                colors = NavigationBarItemDefaults.colors(),
            )
        }
    }
}