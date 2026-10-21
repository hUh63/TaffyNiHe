package top.wkbin.taixu.ui.browser.snapshot

import top.wkbin.taixu.core.browser.PageSnapshot

data class SnapshotSheetState(
    val url: String,
    val title: String,
    val snapshot: PageSnapshot,
)
