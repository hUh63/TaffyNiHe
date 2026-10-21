package top.wkbin.taixu.ui.workspace

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import top.wkbin.taixu.feature.workspace.R
import top.wkbin.taixu.runtime.ProjectType
import top.wkbin.taixu.ui.components.RuntimeIconName

@Composable
internal fun projectImportTypeOptions() = listOf(
    ProjectType.ANDROID to ("Android" to RuntimeIconName.Android),
    ProjectType.FLUTTER to ("Flutter" to RuntimeIconName.Flutter),
    ProjectType.REVERSE to (stringResource(R.string.workspace_template_reverse) to RuntimeIconName.Reverse),
    ProjectType.GENERAL to (stringResource(R.string.workspace_template_empty) to RuntimeIconName.Code),
    ProjectType.TAIXU_PLUGIN to (stringResource(R.string.workspace_project_taixu_plugin) to RuntimeIconName.Code),
)
