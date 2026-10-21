package top.wkbin.taixu.ui.bot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import top.wkbin.taixu.core.model.bot.Bot
import top.wkbin.taixu.feature.bot.R
import top.wkbin.taixu.ui.components.RuntimeAlertDialog
import top.wkbin.taixu.ui.components.RuntimeButton
import top.wkbin.taixu.ui.components.RuntimeCircularProgressIndicator
import top.wkbin.taixu.ui.components.RuntimeFilterChip
import top.wkbin.taixu.ui.components.RuntimeIcon
import top.wkbin.taixu.ui.components.RuntimeIconButton
import top.wkbin.taixu.ui.components.RuntimeIconName
import top.wkbin.taixu.ui.components.RuntimeSwitch
import top.wkbin.taixu.ui.components.RuntimeTextButton
import top.wkbin.taixu.ui.components.RuntimeTopBar
import top.wkbin.taixu.ui.components.StatusBadge

@Composable
fun BotDetailScreen(
    botId: String,
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit = {},
    viewModel: BotDetailViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val profiles by viewModel.modelProfiles.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var memoryPath by rememberSaveable(botId) { mutableStateOf<String?>(null) }
    var memoryHash by rememberSaveable(botId) { mutableStateOf<String?>(null) }
    val bot = state.bot

    LaunchedEffect(botId) { viewModel.bind(botId) }
    LaunchedEffect(Unit) { viewModel.notices.collect { snackbarHostState.showSnackbar(it) } }
    LaunchedEffect(Unit) { viewModel.deleted.collect { onBack() } }

    Scaffold(
        topBar = {
            RuntimeTopBar(
                title = bot?.name ?: stringResource(R.string.fbot_loading),
                onBack = onBack,
                statusText = bot?.role,
                actions = {
                    RuntimeIconButton(
                        onClick = { bot?.let { onOpenSession(it.sessionId) } },
                        enabled = bot != null,
                        contentDescription = stringResource(R.string.fbot_open_session),
                    ) {
                        RuntimeIcon(RuntimeIconName.Chat, Modifier.size(20.dp))
                    }
                    RuntimeIconButton(
                        onClick = viewModel::exportBot,
                        enabled = bot != null && !state.busy,
                        contentDescription = stringResource(R.string.fbot_export),
                    ) {
                        RuntimeIcon(RuntimeIconName.FolderDownload, Modifier.size(20.dp))
                    }
                    RuntimeIconButton(
                        onClick = { confirmDelete = true },
                        enabled = bot != null && !state.busy,
                        contentDescription = stringResource(R.string.fbot_delete),
                    ) {
                        RuntimeIcon(
                            RuntimeIconName.Trash,
                            Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (bot == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (state.loading) {
                    RuntimeCircularProgressIndicator(Modifier.size(28.dp))
                } else {
                    Text(
                        stringResource(R.string.fbot_bot_missing),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    R.string.fbot_tab_persona to RuntimeIconName.Bot,
                    R.string.fbot_tab_memory to RuntimeIconName.History,
                    R.string.fbot_tab_schedule to RuntimeIconName.Alarm,
                ).forEachIndexed { index, (label, icon) ->
                    RuntimeFilterChip(
                        selected = tab == index,
                        onClick = { tab = index },
                        label = { Text(stringResource(label)) },
                        leadingIcon = { RuntimeIcon(icon, Modifier.size(16.dp)) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            when (tab) {
                TAB_PERSONA -> BotPersonaPanel(bot, state.busy, profiles, viewModel::save)
                TAB_MEMORY -> BotMemoryPanel(
                    files = state.memoryFiles,
                    commits = state.commits,
                    busy = state.busy,
                    onRollback = viewModel::rollback,
                    onOpenFile = { memoryPath = it },
                    onOpenDiff = { memoryHash = it },
                )
                else -> BotSchedulePanel(
                    schedules = state.schedules,
                    deliveries = state.deliveries,
                    groups = state.groups,
                    busy = state.busy,
                    onAdd = viewModel::addSchedule,
                    onToggle = viewModel::setScheduleEnabled,
                    onDelete = viewModel::deleteSchedule,
                )
            }
        }
    }

    memoryPath?.let { path ->
        key(botId, path) {
            BotMemoryEditorDialog(path, { viewModel.closeMemoryDraft(botId, path); memoryPath = null },
                state = viewModel.memoryDraft(botId, path),
                load = { viewModel.readMemoryDocument(botId, path) },
                save = { document, content -> viewModel.saveMemoryDocument(botId, document, content) })
        }
    }
    memoryHash?.let { hash ->
        key(botId, hash) { BotMemoryDiffDialog(hash, { memoryHash = null }) { viewModel.memoryDiff(botId, hash) } }
    }
    if (confirmDelete && bot != null) {
        RuntimeAlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.fbot_delete_bot_title)) },
            text = { Text(stringResource(R.string.fbot_delete_bot_confirm, bot.name)) },
            confirmButton = {
                RuntimeButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteBot()
                }) { Text(stringResource(R.string.fbot_delete)) }
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
private fun BotPersonaPanel(
    bot: Bot,
    busy: Boolean,
    profiles: List<top.wkbin.taixu.core.database.AiModelEntity>,
    onSave: (name: String, role: String, persona: String, modelId: String, modelVariant: String?, isMainBot: Boolean, avatarSeed: String, allowModelSelection: Boolean) -> Unit,
) {
    var name by rememberSaveable(bot.id) { mutableStateOf(bot.name) }
    var role by rememberSaveable(bot.id) { mutableStateOf(bot.role) }
    var persona by rememberSaveable(bot.id) { mutableStateOf(bot.systemPrompt) }
    var modelId by rememberSaveable(bot.id) { mutableStateOf(bot.modelId.orEmpty()) }
    var modelVariant by rememberSaveable(bot.id) { mutableStateOf(bot.modelVariant) }
    var allowModelSelection by rememberSaveable(bot.id) { mutableStateOf(bot.allowModelSelection) }
    LaunchedEffect(bot.modelId, bot.modelVariant, bot.allowModelSelection) {
        modelId = bot.modelId.orEmpty()
        modelVariant = bot.modelVariant
        allowModelSelection = bot.allowModelSelection
    }
    var isMain by rememberSaveable(bot.id) { mutableStateOf(bot.isMainBot) }
    var avatarSeed by rememberSaveable(bot.id) { mutableStateOf(bot.avatarSeed) }
    var avatarLoading by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        bot.name,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (bot.isMainBot) StatusBadge(stringResource(R.string.fbot_main_bot))
                }
                Text(
                    profiles.firstOrNull { it.id == bot.modelId }?.let { "${it.name} / ${bot.modelVariant ?: it.model.substringBefore(',')}" }
                        ?: bot.modelId ?: stringResource(R.string.fbot_model_default),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        BotAvatarEditor(avatarSeed, name.ifBlank { bot.name }, bot.colorHex, !busy,
            onChange = { avatarSeed = it }, onLoading = { avatarLoading = it })
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(40) },
            label = { Text(stringResource(R.string.fbot_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = role,
            onValueChange = { role = it.take(40) },
            label = { Text(stringResource(R.string.fbot_role)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = persona,
            onValueChange = { persona = it },
            label = { Text(stringResource(R.string.fbot_persona)) },
            placeholder = { Text(stringResource(R.string.fbot_persona_placeholder)) },
            minLines = 6,
            maxLines = 14,
            modifier = Modifier.fillMaxWidth(),
        )
        BotModelPicker(profiles, modelId.takeIf(String::isNotBlank), modelVariant, !busy) { id, concrete ->
            modelId = id.orEmpty()
            modelVariant = concrete
        }
        BotModelPolicySwitch(allowModelSelection, !busy) { allowModelSelection = it }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.fbot_main_switch), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.fbot_main_bot),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RuntimeSwitch(checked = isMain, onCheckedChange = { isMain = it }, enabled = !busy)
        }
        RuntimeButton(
            onClick = { onSave(name, role, persona, modelId, modelVariant, isMain, avatarSeed, allowModelSelection) },
            enabled = !busy && !avatarLoading && name.isNotBlank() && role.isNotBlank() && persona.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.fbot_save)) }
    }
}

private const val TAB_PERSONA = 0
private const val TAB_MEMORY = 1
