package top.wkbin.taixu.ui.settings.search

import androidx.compose.runtime.Composable
import top.wkbin.taixu.ui.components.FoldablePreviews
import top.wkbin.taixu.ui.components.RuntimePreview

@FoldablePreviews
@Composable
private fun SettingsSearchScreenPreview() {
    RuntimePreview {
        SettingsSearchScreen(onBack = { }, onNavigateToTarget = { _ -> })
    }
}
