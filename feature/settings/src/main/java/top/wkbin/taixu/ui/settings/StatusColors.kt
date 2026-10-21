package top.wkbin.taixu.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import top.wkbin.taixu.ui.theme.semanticColors

/** Reuse the theme's harmonized status roles across settings and detail pages. */
@Composable
internal fun successStatusColor(): Color = semanticColors().success

@Composable
internal fun warningStatusColor(): Color = semanticColors().warning
