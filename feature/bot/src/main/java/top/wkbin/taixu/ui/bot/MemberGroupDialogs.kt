package top.wkbin.taixu.ui.bot

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.UUID
import top.wkbin.taixu.core.model.bot.Bot
import top.wkbin.taixu.core.model.bot.BotMemberGroup
import top.wkbin.taixu.feature.bot.R
import top.wkbin.taixu.ui.components.RuntimeAlertDialog
import top.wkbin.taixu.ui.components.RuntimeButton
import top.wkbin.taixu.ui.components.RuntimeFilledTonalButton
import top.wkbin.taixu.ui.components.RuntimeIcon
import top.wkbin.taixu.ui.components.RuntimeIconButton
import top.wkbin.taixu.ui.components.RuntimeIconName
import top.wkbin.taixu.ui.components.RuntimeTextButton

@Composable
internal fun MemberGroupsDialog(
    groups: List<BotMemberGroup>,
    bots: List<Bot>,
    onDismiss: () -> Unit,
    onSave: (BotMemberGroup) -> Unit,
    onDelete: (BotMemberGroup) -> Unit,
) {
    var editing by remember { mutableStateOf<BotMemberGroup?>(null) }
    var isNew by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<BotMemberGroup?>(null) }

    val current = editing
    if (current == null) {
        RuntimeAlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.fbot_member_groups)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.fbot_member_groups_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (groups.isEmpty()) {
                        Text(
                            stringResource(R.string.fbot_member_group_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    groups.forEach { group ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    isNew = false
                                    editing = group
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(group.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    stringResource(R.string.fbot_member_group_member_count, group.memberIds.size),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            RuntimeIconButton(
                                onClick = { pendingDelete = group },
                                contentDescription = stringResource(R.string.fbot_delete),
                            ) {
                                RuntimeIcon(
                                    RuntimeIconName.Trash,
                                    Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                    RuntimeFilledTonalButton(
                        onClick = {
                            isNew = true
                            editing = BotMemberGroup(
                                id = UUID.randomUUID().toString(),
                                name = "",
                                createdAt = System.currentTimeMillis(),
                            )
                        },
                    ) {
                        Text(stringResource(R.string.fbot_member_group_new))
                    }
                }
            },
            confirmButton = {
                RuntimeButton(onClick = onDismiss) { Text(stringResource(R.string.fbot_close)) }
            },
        )
    } else {
        MemberGroupEditorDialog(
            group = current,
            bots = bots,
            isNew = isNew,
            onDismiss = { editing = null },
            onSave = { saved ->
                editing = null
                onSave(saved)
            },
        )
    }
    pendingDelete?.let { group ->
        RuntimeAlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.fbot_member_group_delete_title)) },
            text = { Text(stringResource(R.string.fbot_member_group_delete_confirm, group.name)) },
            confirmButton = {
                RuntimeButton(onClick = {
                    onDelete(group)
                    pendingDelete = null
                }) { Text(stringResource(R.string.fbot_delete)) }
            },
            dismissButton = {
                RuntimeTextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.fbot_cancel))
                }
            },
        )
    }
}

@Composable
private fun MemberGroupEditorDialog(
    group: BotMemberGroup,
    bots: List<Bot>,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (BotMemberGroup) -> Unit,
) {
    var name by rememberSaveable(group.id) { mutableStateOf(group.name) }
    var selected by remember(group.id) { mutableStateOf(group.memberIds.toSet()) }
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (isNew) R.string.fbot_member_group_new else R.string.fbot_member_group_edit,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text(stringResource(R.string.fbot_member_group_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (bots.isEmpty()) {
                    Text(
                        stringResource(R.string.fbot_need_bots),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    MemberSelection(
                        bots = bots,
                        selectedIds = selected,
                        onSelectionChange = { selected = it },
                    )
                }
            }
        },
        confirmButton = {
            RuntimeButton(
                onClick = {
                    onSave(
                        group.copy(
                            name = name.trim(),
                            memberIds = bots.map { it.id }.filter { it in selected },
                        ),
                    )
                },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.fbot_save)) }
        },
        dismissButton = {
            RuntimeTextButton(onClick = onDismiss) { Text(stringResource(R.string.fbot_cancel)) }
        },
    )
}
