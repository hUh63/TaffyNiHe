package top.wkbin.taixu.ui.bot

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import top.wkbin.taixu.core.model.bot.Bot
import androidx.compose.ui.text.input.TextFieldValue
import top.wkbin.taixu.core.model.bot.GroupAttachment
import top.wkbin.taixu.feature.bot.R
import top.wkbin.taixu.ui.components.EmptyPanel
import top.wkbin.taixu.ui.components.RuntimeAlertDialog
import top.wkbin.taixu.ui.components.RuntimeButton
import top.wkbin.taixu.ui.components.RuntimeCircularProgressIndicator
import top.wkbin.taixu.ui.components.RuntimeIcon
import top.wkbin.taixu.ui.components.RuntimeIconButton
import top.wkbin.taixu.ui.components.RuntimeIconName
import top.wkbin.taixu.ui.components.RuntimeTextButton
import top.wkbin.taixu.ui.components.RuntimeTopBar

enum class GroupChatViewMode {
    CHAT,
    STUDIO,
}

@Composable
fun GroupChatScreen(
    groupId: String,
    onBack: () -> Unit,
    onOpenBot: (String) -> Unit = {},
    viewModel: GroupChatViewModel = koinViewModel(),
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val members by viewModel.members.collectAsStateWithLifecycle()
    val allBots by viewModel.allBots.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val activeAssignments by viewModel.activeAssignments.collectAsStateWithLifecycle()
    val workAssignments by viewModel.groupAssignments.collectAsStateWithLifecycle()
    val pendingApprovals by viewModel.pendingApprovals.collectAsStateWithLifecycle()
    val exportFile by viewModel.exportFile.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var following by remember { mutableStateOf(true) }
    var anchorId by remember { mutableStateOf<String?>(null) }
    val unseen = if (following) 0 else countUnseen(messages, anchorId)
    var input by rememberSaveable(groupId, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var pendingAttachments by remember { mutableStateOf<List<GroupAttachment>>(emptyList()) }
    var showMembers by rememberSaveable { mutableStateOf(false) }
    var viewMode by rememberSaveable(groupId) { mutableStateOf(GroupChatViewMode.CHAT) }
    BackHandler(enabled = viewMode == GroupChatViewMode.STUDIO) {
        viewMode = GroupChatViewMode.CHAT
    }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val attachmentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val remaining = MAX_ATTACHMENTS - pendingAttachments.size
        if (uris.size > remaining) {
            scope.launch {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.fbot_attachment_limit, MAX_ATTACHMENTS),
                )
            }
        }
        pendingAttachments = pendingAttachments + pickAttachments(context, uris.take(remaining))
    }
    val memberById = remember(members) { members.associateBy(Bot::id) }
    val chatEntries = remember(messages) { buildChatEntries(messages) }
    val workSummary = remember(workAssignments, activeAssignments, messages) { summarizeGroupWork(workAssignments, activeAssignments, messages) }
    val busy = workSummary.active > 0
    val yesterdayLabel = stringResource(R.string.fbot_yesterday)
    val admin = remember(members, state.group?.adminBotId) {
        members.firstOrNull { it.id == state.group?.adminBotId }
    }
    val statusText = if (admin != null) {
        stringResource(R.string.fbot_group_meta, members.size, admin.name)
    } else {
        stringResource(R.string.fbot_group_members, members.size)
    }

    LaunchedEffect(groupId) { viewModel.bind(groupId) }
    LaunchedEffect(Unit) { viewModel.notices.collect { snackbarHostState.showSnackbar(it) } }
    LaunchedEffect(exportFile) {
        exportFile?.let { file ->
            runCatching { shareDiagnostics(context, file) }
                .onFailure {
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.fbot_share_failed, it.message.orEmpty()),
                    )
                }
            viewModel.consumeExportFile()
        }
    }
    GroupChatScrollEffects(
        listState = listState,
        messages = messages,
        following = following,
        onFollowingChange = { following = it },
        onAnchorChange = { anchorId = it },
    )

    val studio = viewMode == GroupChatViewMode.STUDIO
    Scaffold(
        contentWindowInsets = if (studio) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
        topBar = {
            if (!studio) {
                GroupChatTopBar(
                    viewMode = viewMode,
                    groupName = state.group?.name.orEmpty(),
                    statusText = statusText,
                    busy = busy,
                    workSummary = workSummary,
                    exporting = state.exporting,
                    onBack = onBack,
                    onSwitchMode = { viewMode = it },
                    onExportDiagnostics = viewModel::exportDiagnostics,
                    onShowInfo = { showInfo = true },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = if (studio) Color(PixelOfficeRoom.FLOOR) else MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .then(
                    if (studio) Modifier
                    else Modifier.padding(padding).background(groupBackgroundBrush(state.group?.background)),
                ),
        ) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (studio) {
                    GroupCyberStudio(
                        members = members,
                        messages = messages,
                        assignments = workAssignments,
                        onSelectBot = { bot -> input = insertGroupMention(input, bot.name) },
                        onOpenBot = onOpenBot,
                        onCancelAssignment = viewModel::cancelAssignment,
                        onContinueAssignment = viewModel::continueAssignment,
                        onBackToChat = { viewMode = GroupChatViewMode.CHAT },
                        waitingSessions = pendingApprovals.map { OfficeWait(it.sessionId, it.createdAt, it.expiresAt) },
                        modifier = Modifier.fillMaxSize(),
                        footer = {
                            GroupPendingActions(viewModel)
                            PixelOfficeComposer(
                                members = members,
                                value = input,
                                attachments = pendingAttachments,
                                sending = state.sending,
                                busy = busy,
                                stopping = state.stopping,
                                onValueChange = { input = it },
                                onMention = { name -> input = insertGroupMention(input, name) },
                                onAttach = { attachmentPicker.launch(arrayOf("*/*")) },
                                onRemoveAttachment = { pendingAttachments = pendingAttachments - it },
                                onSend = {
                                    following = true
                                    anchorId = null
                                    viewModel.send(input.text, pendingAttachments)
                                    input = TextFieldValue()
                                    pendingAttachments = emptyList()
                                },
                                onStop = viewModel::stop,
                            )
                        },
                    )
                } else if (messages.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyPanel(
                        icon = RuntimeIconName.Bot,
                        title = stringResource(R.string.fbot_chat_empty_title),
                        description = stringResource(R.string.fbot_chat_empty_hint),
                    )
                }
            } else {
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.Top,
                    ) {
                        items(chatEntries, key = { it.message.id }) { entry ->
                            Column(
                                modifier = Modifier.padding(
                                    top = when {
                                        entry.showSeparator -> 0.dp
                                        entry.firstInGroup -> 10.dp
                                        else -> 2.dp
                                    },
                                ),
                            ) {
                                if (entry.showSeparator) {
                                    TimeSeparator(separatorLabel(entry.message.timestamp, yesterdayLabel))
                                }
                                GroupMessageRow(
                                    message = entry.message,
                                    bot = memberById[entry.message.senderId],
                                    firstInGroup = entry.firstInGroup,
                                    lastInGroup = entry.lastInGroup,
                                    onOpenBot = onOpenBot,
                                )
                            }
                        }
                    }
                    if (!following && listState.canScrollForward) {
                        Box(
                            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 10.dp),
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                shape = CircleShape,
                                shadowElevation = 2.dp,
                            ) {
                                RuntimeIconButton(
                                    onClick = {
                                        following = true
                                        anchorId = null
                                        scope.launch { listState.animateToBottom() }
                                    },
                                    contentDescription = if (unseen > 0) {
                                        stringResource(R.string.fbot_unseen_count, unseen)
                                    } else {
                                        stringResource(R.string.fbot_jump_latest)
                                    },
                                ) {
                                    RuntimeIcon(RuntimeIconName.ChevronDown, Modifier.size(18.dp))
                                }
                            }
                            if (unseen > 0) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = CircleShape,
                                    modifier = Modifier.align(Alignment.TopEnd),
                                ) {
                                    Text(
                                        text = if (unseen > 99) "99+" else "$unseen",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            }
            if (!studio) {
            GroupPendingActions(viewModel)
            GroupChatComposer(
                members = members,
                value = input,
                attachments = pendingAttachments,
                sending = state.sending,
                busy = busy,
                stopping = state.stopping,
                onValueChange = { input = it },
                onMention = { name ->
                    input = insertGroupMention(input, name)
                },
                onAttach = { attachmentPicker.launch(arrayOf("*/*")) },
                onRemoveAttachment = { attachment ->
                    pendingAttachments = pendingAttachments - attachment
                },
                onSend = {
                    following = true
                    anchorId = null
                    viewModel.send(input.text, pendingAttachments)
                    input = TextFieldValue()
                    pendingAttachments = emptyList()
                },
                onStop = viewModel::stop,
            )
            }
        }
    }

    if (showInfo) {
        state.group?.let { group ->
            GroupInfoDialog(
                group = group,
                deleting = state.deleting,
                onDismiss = { showInfo = false },
                onSave = { name, description, background, replyMode ->
                    viewModel.updateGroup(name, description, background, replyMode)
                    showInfo = false
                },
                onDelete = {
                    showInfo = false
                    viewModel.deleteGroup(onBack)
                },
                onManageMembers = {
                    showInfo = false
                    showMembers = true
                },
                onClearMessages = {
                    showInfo = false
                    confirmClear = true
                },
            )
        }
    }
    if (showMembers) {
        GroupMembersDialog(
            allBots = allBots,
            initialMembers = members,
            adminBotId = state.group?.adminBotId.orEmpty(),
            onDismiss = { showMembers = false },
            onConfirm = {
                viewModel.saveMembers(it)
                showMembers = false
            },
        )
    }
    if (confirmClear) {
        RuntimeAlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.fbot_clear_title)) },
            text = { Text(stringResource(R.string.fbot_clear_confirm)) },
            confirmButton = {
                RuntimeButton(onClick = {
                    confirmClear = false
                    viewModel.clearMessages()
                }) { Text(stringResource(R.string.fbot_clear_messages)) }
            },
            dismissButton = {
                RuntimeTextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.fbot_cancel))
                }
            },
        )
    }
}
