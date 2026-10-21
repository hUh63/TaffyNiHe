package top.wkbin.taixu.ui.chat

import androidx.compose.runtime.Composable
import top.wkbin.taixu.ui.components.FoldablePreviews
import top.wkbin.taixu.ui.components.RuntimePreview

@FoldablePreviews
@Composable
private fun ChatScreenPreview() {
    RuntimePreview {
        ChatScreen(onNavigate = { _ -> }, onOpenNavigation = { })
    }
}
