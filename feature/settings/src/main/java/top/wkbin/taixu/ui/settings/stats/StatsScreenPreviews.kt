package top.wkbin.taixu.ui.settings.stats

import androidx.compose.runtime.Composable
import top.wkbin.taixu.ui.components.FoldablePreviews
import top.wkbin.taixu.ui.components.RuntimePreview

@FoldablePreviews
@Composable
private fun StatsScreenPreview() {
    RuntimePreview {
        StatsScreen(onBack = { })
    }
}
