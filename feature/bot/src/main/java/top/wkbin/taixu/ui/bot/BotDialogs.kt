package top.wkbin.taixu.ui.bot

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import top.wkbin.taixu.core.model.bot.Bot
import top.wkbin.taixu.core.model.bot.BotMemberGroup
import top.wkbin.taixu.core.model.bot.GroupChat
import top.wkbin.taixu.feature.bot.R
import top.wkbin.taixu.ui.components.RuntimeAlertDialog
import top.wkbin.taixu.ui.components.RuntimeButton
import top.wkbin.taixu.ui.components.RuntimeCheckbox
import top.wkbin.taixu.ui.components.RuntimeFilterChip
import top.wkbin.taixu.ui.components.RuntimeTextButton

@Composable
fun CreateBotDialog(
    groups: List<GroupChat>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onCreate: (name: String, role: String, persona: String, groupId: String?, avatarSeed: String?) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var role by rememberSaveable { mutableStateOf("") }
    var persona by rememberSaveable { mutableStateOf("") }
    var groupId by rememberSaveable { mutableStateOf<String?>(null) }
    var avatarSeed by rememberSaveable { mutableStateOf<String?>(null) }
    var avatarLoading by remember { mutableStateOf(false) }
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.fbot_create_bot)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BotAvatarEditor(avatarSeed ?: name, name, "#3D5AFE", !busy,
                    onChange = { avatarSeed = it }, onLoading = { avatarLoading = it })
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text(stringResource(R.string.fbot_name)) },
                    placeholder = { Text(stringResource(R.string.fbot_bot_name_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = role,
                    onValueChange = { role = it.take(40) },
                    label = { Text(stringResource(R.string.fbot_role)) },
                    placeholder = { Text(stringResource(R.string.fbot_bot_role_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = persona,
                    onValueChange = { persona = it },
                    label = { Text(stringResource(R.string.fbot_persona)) },
                    placeholder = { Text(stringResource(R.string.fbot_persona_placeholder)) },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (groups.isNotEmpty()) {
                    Text(
                        stringResource(R.string.fbot_join_group),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RuntimeFilterChip(
                            selected = groupId == null,
                            onClick = { groupId = null },
                            label = { Text(stringResource(R.string.fbot_none)) },
                        )
                        groups.forEach { group ->
                            RuntimeFilterChip(
                                selected = groupId == group.id,
                                onClick = { groupId = group.id },
                                label = { Text(group.name) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            RuntimeButton(
                onClick = { onCreate(name, role, persona, groupId, avatarSeed) },
                enabled = !busy && !avatarLoading && name.isNotBlank() && role.isNotBlank() && persona.isNotBlank(),
            ) { Text(stringResource(R.string.fbot_create)) }
        },
        dismissButton = {
            RuntimeTextButton(onClick = onDismiss) { Text(stringResource(R.string.fbot_cancel)) }
        },
    )
}

@Composable
fun CreateGroupDialog(
    bots: List<Bot>,
    memberGroups: List<BotMemberGroup>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onCreate: (name: String, description: String, adminBotId: String, memberIds: Set<String>) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var adminBotId by rememberSaveable { mutableStateOf(bots.firstOrNull()?.id.orEmpty()) }
    var selectedIds by remember {
        mutableStateOf(bots.firstOrNull()?.id?.let { setOf(it) } ?: emptySet<String>())
    }
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.fbot_create_group)) },
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
                if (bots.isEmpty()) {
                    Text(
                        stringResource(R.string.fbot_need_bots),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        stringResource(R.string.fbot_admin_bot),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        bots.forEach { bot ->
                            RuntimeFilterChip(
                                selected = adminBotId == bot.id,
                                onClick = {
                                    adminBotId = bot.id
                                    selectedIds = selectedIds + bot.id
                                },
                                label = { Text(bot.name) },
                            )
                        }
                    }
                    MemberSelection(
                        bots = bots,
                        selectedIds = selectedIds,
                        onSelectionChange = { selectedIds = it },
                        memberGroups = memberGroups,
                        lockedBotId = adminBotId,
                    )
                }
            }
        },
        confirmButton = {
            RuntimeButton(
                onClick = { onCreate(name, description, adminBotId, selectedIds + adminBotId) },
                enabled = !busy && bots.isNotEmpty() && name.isNotBlank() && adminBotId.isNotBlank(),
            ) { Text(stringResource(R.string.fbot_create)) }
        },
        dismissButton = {
            RuntimeTextButton(onClick = onDismiss) { Text(stringResource(R.string.fbot_cancel)) }
        },
    )
}

@Composable
fun GroupMembersDialog(
    allBots: List<Bot>,
    initialMembers: List<Bot>,
    adminBotId: String,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var selected by remember {
        mutableStateOf(initialMembers.map { it.id }.toSet() + adminBotId)
    }
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.fbot_members_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(R.string.fbot_members_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                allBots.forEach { bot ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RuntimeCheckbox(
                            checked = bot.id in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + bot.id else selected - bot.id
                            },
                            enabled = bot.id != adminBotId,
                        )
                        BotAvatar(bot, 28.dp)
                        Column(Modifier.padding(start = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(bot.name, style = MaterialTheme.typography.bodyMedium)
                                if (bot.id == adminBotId) {
                                    Text(
                                        text = stringResource(R.string.fbot_main_bot),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(start = 6.dp),
                                    )
                                }
                            }
                            Text(
                                bot.role,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            RuntimeButton(onClick = { onConfirm(selected + adminBotId) }) {
                Text(stringResource(R.string.fbot_save))
            }
        },
        dismissButton = {
            RuntimeTextButton(onClick = onDismiss) { Text(stringResource(R.string.fbot_cancel)) }
        },
    )
}
