package top.wkbin.taixu.ui.settings

import androidx.compose.runtime.Composable
import top.wkbin.taixu.ui.components.FoldablePreviews
import top.wkbin.taixu.ui.components.RuntimePreview

@FoldablePreviews
@Composable
private fun PhoneAgentSettingsScreenPreview() {
    RuntimePreview {
        PhoneAgentSettingsScreen(onBack = { })
    }
}
