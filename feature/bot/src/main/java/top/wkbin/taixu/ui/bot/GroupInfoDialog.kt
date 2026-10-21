package top.wkbin.taixu.ui.bot

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.wkbin.taixu.core.model.bot.GroupChat
import top.wkbin.taixu.core.model.bot.GroupReplyMode
import top.wkbin.taixu.feature.bot.R
import top.wkbin.taixu.ui.components.RuntimeAlertDialog
import top.wkbin.taixu.ui.components.RuntimeButton
import top.wkbin.taixu.ui.components.RuntimeFilterChip
import top.wkbin.taixu.ui.components.RuntimeTextButton

private val BACKGROUND_STYLES = listOf("default", "warm", "mint", "night")

@Composable
internal fun GroupInfoDialog(
    group: GroupChat,
    deleting: Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String, background: String, replyMode: GroupReplyMode) -> Unit,
    onDelete: () -> Unit,
    onManageMembers: () -> Unit,
    onClearMessages: () -> Unit,
) {
    var name by rememberSaveable(group.id) { mutableStateOf(group.name) }
    var description by rememberSaveable(group.id) { mutableStateOf(group.description) }
    var background by rememberSaveable(group.id) {
        mutableStateOf(group.background.ifBlank { "default" })
    }
    var replyMode by rememberSaveable(group.id) { mutableStateOf(group.replyMode) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.fbot_group_info)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text(stringResource(R.string.fbot_group_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(200) },
                    label = { Text(stringResource(R.string.fbot_group_desc)) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.fbot_reply_mode),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GroupReplyMode.entries.forEach { mode ->
                        RuntimeFilterChip(
                            selected = replyMode == mode,
                            onClick = { replyMode = mode },
                            label = { Text(replyModeLabel(mode)) },
                        )
                    }
                }
                Text(
                    stringResource(R.string.fbot_reply_mode_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.fbot_background),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BACKGROUND_STYLES.forEach { style ->
                        RuntimeFilterChip(
                            selected = background == style,
                            onClick = { background = style },
                            label = { Text(backgroundLabel(style)) },
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                RuntimeTextButton(onClick = onManageMembers, enabled = !deleting) {
                    Text(stringResource(R.string.fbot_manage_members))
                }
                RuntimeTextButton(onClick = onClearMessages, enabled = !deleting) {
                    Text(stringResource(R.string.fbot_clear_messages))
                }
                RuntimeTextButton(onClick = { confirmDelete = true }, enabled = !deleting) {
                    Text(
                        text = stringResource(R.string.fbot_group_disband),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            RuntimeButton(
                onClick = { onSave(name, description, background, replyMode) },
                enabled = name.isNotBlank() && !deleting,
            ) { Text(stringResource(R.string.fbot_save)) }
        },
        dismissButton = {
            RuntimeTextButton(onClick = onDismiss) { Text(stringResource(R.string.fbot_cancel)) }
        },
    )
    if (confirmDelete) {
        RuntimeAlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.fbot_group_disband)) },
            text = { Text(stringResource(R.string.fbot_group_disband_confirm)) },
            confirmButton = {
                RuntimeButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                    enabled = !deleting,
                ) { Text(stringResource(R.string.fbot_delete)) }
            },
            dismissButton = {
                RuntimeTextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.fbot_cancel))
                }
            },
        )
    }
}

@Composable
private fun replyModeLabel(mode: GroupReplyMode): String = when (mode) {
    GroupReplyMode.ADMIN_FIRST -> stringResource(R.string.fbot_reply_mode_admin)
    GroupReplyMode.ALL_MEMBERS -> stringResource(R.string.fbot_reply_mode_all)
}

@Composable
private fun backgroundLabel(style: String): String = when (style) {
    "warm" -> stringResource(R.string.fbot_background_warm)
    "mint" -> stringResource(R.string.fbot_background_mint)
    "night" -> stringResource(R.string.fbot_background_night)
    else -> stringResource(R.string.fbot_background_default)
}

@Composable
internal fun groupBackgroundBrush(style: String?): Brush = when (style) {
    "warm" -> Brush.verticalGradient(
        listOf(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.07f),
            MaterialTheme.colorScheme.surface,
        ),
    )
    "mint" -> Brush.verticalGradient(
        listOf(
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.09f),
            MaterialTheme.colorScheme.surface,
        ),
    )
    "night" -> Brush.verticalGradient(
        listOf(
            MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f),
            MaterialTheme.colorScheme.surface,
        ),
    )
    else -> SolidColor(Color.Transparent)
}
