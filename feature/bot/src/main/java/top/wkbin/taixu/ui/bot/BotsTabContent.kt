package top.wkbin.taixu.ui.bot

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.wkbin.taixu.core.model.bot.Bot
import top.wkbin.taixu.core.model.bot.BotMemberGroup
import top.wkbin.taixu.core.model.bot.GroupChat
import top.wkbin.taixu.core.model.bot.MarketBotItem
import top.wkbin.taixu.feature.bot.R
import top.wkbin.taixu.ui.components.EmptyPanel
import top.wkbin.taixu.ui.components.RuntimeCard
import top.wkbin.taixu.ui.components.RuntimeFilledTonalButton
import top.wkbin.taixu.ui.components.RuntimeFilterChip
import top.wkbin.taixu.ui.components.RuntimeIcon
import top.wkbin.taixu.ui.components.RuntimeIconButton
import top.wkbin.taixu.ui.components.RuntimeIconName
import top.wkbin.taixu.ui.components.StatusBadge

@Composable
internal fun BotList(
    bots: List<Bot>,
    modelProfiles: List<top.wkbin.taixu.core.database.AiModelEntity>?,
    memberGroups: List<BotMemberGroup>,
    selectedGroupId: String?,
    onSelectGroup: (String?) -> Unit,
    onManageGroups: () -> Unit,
    onOpenBot: (String) -> Unit,
) {
    val visibleBots = remember(bots, memberGroups, selectedGroupId) {
        val memberIds = selectedGroupId?.let { id -> memberGroups.firstOrNull { it.id == id } }
            ?.memberIds
            ?.toSet()
        if (memberIds == null) bots else bots.filter { it.id in memberIds }
    }
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "member-group-filter") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RuntimeFilterChip(
                        selected = selectedGroupId == null,
                        onClick = { onSelectGroup(null) },
                        label = { Text(stringResource(R.string.fbot_market_all)) },
                    )
                    memberGroups.forEach { group ->
                        RuntimeFilterChip(
                            selected = selectedGroupId == group.id,
                            onClick = { onSelectGroup(group.id) },
                            label = { Text(group.name) },
                        )
                    }
                }
                RuntimeIconButton(
                    onClick = onManageGroups,
                    contentDescription = stringResource(R.string.fbot_manage_member_groups),
                ) {
                    RuntimeIcon(RuntimeIconName.Tune, Modifier.size(20.dp))
                }
            }
        }
        if (visibleBots.isEmpty()) {
            item {
                EmptyPanel(
                    icon = RuntimeIconName.Bot,
                    title = stringResource(
                        if (bots.isEmpty()) R.string.fbot_empty_bots_title else R.string.fbot_member_group_filter_empty,
                    ),
                    description = stringResource(
                        if (bots.isEmpty()) R.string.fbot_empty_bots_hint else R.string.fbot_member_group_filter_hint,
                    ),
                )
            }
        }
        items(visibleBots, key = { it.id }) { bot ->
            RuntimeCard(onClick = { onOpenBot(bot.id) }, modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BotAvatar(bot, 52.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                bot.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (bot.isMainBot) StatusBadge(stringResource(R.string.fbot_main_bot))
                        }
                        Text(
                            bot.role,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        BotModelSummary(bot, modelProfiles)
                    }
                    RuntimeIcon(
                        RuntimeIconName.ChevronRight,
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
internal fun GroupList(
    groups: List<GroupChat>,
    adminName: (String) -> String,
    onOpenGroup: (String) -> Unit,
    onDeleteGroup: (GroupChat) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (groups.isEmpty()) {
            item {
                EmptyPanel(
                    icon = RuntimeIconName.Chat,
                    title = stringResource(R.string.fbot_empty_groups_title),
                    description = stringResource(R.string.fbot_empty_groups_hint),
                )
            }
        }
        items(groups, key = { it.id }) { group ->
            RuntimeCard(onClick = { onOpenGroup(group.id) }, modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BotGroupBadge(group.name)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            group.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(R.string.fbot_group_admin, adminName(group.adminBotId).ifBlank { "—" }),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (group.description.isNotBlank()) {
                            Text(
                                group.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    RuntimeIconButton(
                        onClick = { onDeleteGroup(group) },
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
        }
    }
}

@Composable
internal fun MarketList(
    items: List<MarketBotItem>,
    installedNames: Set<String>,
    installingId: String?,
    onInstall: (MarketBotItem) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var department by rememberSaveable { mutableStateOf("") }
    val departments = remember(items) { items.mapNotNull { it.departmentName }.distinct() }
    val filtered = remember(items, query, department) {
        val term = query.trim()
        items.filter { item ->
            (department.isBlank() || item.departmentName == department) &&
                (term.isBlank() || item.name.contains(term, true) ||
                    item.role.contains(term, true) || item.description.contains(term, true))
        }
    }
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.fbot_market_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    RuntimeFilterChip(
                        selected = department.isBlank(),
                        onClick = { department = "" },
                        label = { Text(stringResource(R.string.fbot_market_all)) },
                    )
                    departments.forEach { name ->
                        RuntimeFilterChip(
                            selected = department == name,
                            onClick = { department = name },
                            label = { Text(name) },
                        )
                    }
                }
            }
        }
        if (filtered.isEmpty()) {
            item {
                EmptyPanel(
                    icon = RuntimeIconName.Hub,
                    title = stringResource(R.string.fbot_tab_market),
                    description = stringResource(R.string.fbot_market_empty),
                )
            }
        }
        items(filtered, key = { it.id }) { item ->
            val installed = item.name in installedNames
            RuntimeCard(modifier = Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BotAvatar(seed = item.avatarSeed ?: item.name, colorHex = item.colorHex, size = 48.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(
                            item.role,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                        Text(
                            item.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (item.stars > 0 || item.downloadCount > 0) {
                            Text(
                                stringResource(R.string.fbot_market_stats, item.stars, item.downloadCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                item.author,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(R.string.fbot_market_install_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                    )
                    RuntimeFilledTonalButton(
                        onClick = { onInstall(item) },
                        enabled = installingId == null && !installed,
                    ) {
                        Text(
                            stringResource(
                                when {
                                    installed -> R.string.fbot_market_installed
                                    installingId == item.id -> R.string.fbot_installing
                                    else -> R.string.fbot_install
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BotGroupBadge(name: String) {
    val color = MaterialTheme.colorScheme.primary
    RuntimeCard(
        containerColor = color.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.size(52.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            RuntimeIcon(RuntimeIconName.Chat, Modifier.size(20.dp), tint = color)
            Text(
                name.take(1),
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
    }
}
