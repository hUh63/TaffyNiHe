package top.wkbin.taixu.ui.bot

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.wkbin.taixu.core.database.repository.BotScheduleRepository
import top.wkbin.taixu.core.database.repository.GroupChatRepository
import top.wkbin.taixu.core.database.repository.PersonaBotRepository
import top.wkbin.taixu.core.model.bot.Bot
import top.wkbin.taixu.core.model.bot.BotMemoryFile
import top.wkbin.taixu.core.model.bot.BotScheduleDelivery
import top.wkbin.taixu.core.model.bot.BotSchedule
import top.wkbin.taixu.core.model.bot.GroupChat
import top.wkbin.taixu.core.model.bot.MemoryCommit
import top.wkbin.taixu.harness.bot.BotOrchestrator
import top.wkbin.taixu.harness.bot.archive.BotPackageService
import top.wkbin.taixu.harness.bot.memory.BotMemoryManager
import top.wkbin.taixu.harness.bot.schedule.BotScheduleWakeup
import top.wkbin.taixu.harness.bot.schedule.CronSchedule

data class BotDetailUiState(
    val bot: Bot? = null,
    val memoryFiles: List<BotMemoryFile> = emptyList(),
    val commits: List<MemoryCommit> = emptyList(),
    val schedules: List<BotSchedule> = emptyList(),
    val deliveries: List<BotScheduleDelivery> = emptyList(),
    val groups: List<GroupChat> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
)

class BotDetailViewModel(
    private val context: Context,
    private val botRepository: PersonaBotRepository,
    private val groupRepository: GroupChatRepository,
    private val scheduleRepository: BotScheduleRepository,
    private val orchestrator: BotOrchestrator,
    private val memoryManager: BotMemoryManager,
    private val packageService: BotPackageService,
    private val wakeup: BotScheduleWakeup? = null,
    private val models: top.wkbin.taixu.core.database.AiModelRepository,
) : ViewModel() {
    val modelProfiles = models.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _uiState = MutableStateFlow(BotDetailUiState())
    val uiState: StateFlow<BotDetailUiState> = _uiState.asStateFlow()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices = _notices.asSharedFlow()

    private val _deleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val deleted = _deleted.asSharedFlow()

    private var boundBotId: String? = null
    private val memoryDrafts = mutableMapOf<Pair<String, String>, BotMemoryEditorState>()

    internal fun memoryDraft(botId: String, path: String) = memoryDrafts.getOrPut(botId to path) { BotMemoryEditorState() }
    internal fun closeMemoryDraft(botId: String, path: String) { memoryDrafts.remove(botId to path) }

    fun bind(botId: String) {
        if (boundBotId == botId) return
        boundBotId = botId
        viewModelScope.launch {
            botRepository.observeBot(botId).collect { bot -> _uiState.update { it.copy(bot = bot) } }
        }
        viewModelScope.launch {
            scheduleRepository.observeForBot(botId).collect { list -> _uiState.update { it.copy(schedules = list) } }
        }
        viewModelScope.launch {
            scheduleRepository.observeDeliveries(botId).collect { list -> _uiState.update { it.copy(deliveries = list) } }
        }
        viewModelScope.launch {
            groupRepository.observeGroups().collect { list -> _uiState.update { it.copy(groups = list) } }
        }
        viewModelScope.launch {
            val files = runCatching { memoryManager.listFiles(botId) }.getOrDefault(emptyList())
            val commits = runCatching { memoryManager.getCommitHistory(botId) }.getOrDefault(emptyList())
            _uiState.update { it.copy(memoryFiles = files, commits = commits, loading = false) }
        }
    }

    fun save(name: String, role: String, persona: String, modelId: String, modelVariant: String?, isMainBot: Boolean, avatarSeed: String, allowModelSelection: Boolean) {
        val bot = _uiState.value.bot ?: return
        if (name.isBlank() || role.isBlank() || persona.isBlank()) {
            emit("请填写名称、职责与人格设定")
            return
        }
        launchBusy {
            orchestrator.updateBot(
                botId = bot.id,
                name = name,
                role = role,
                systemPrompt = persona,
                modelId = modelId.trim().takeIf { it.isNotBlank() },
                modelVariant = modelVariant,
                allowModelSelection = allowModelSelection,
                isMainBot = isMainBot,
                avatarSeed = avatarSeed,
            )
            refreshMemory(bot.id)
            "已保存"
        }
    }

    fun deleteBot() {
        val bot = _uiState.value.bot ?: return
        launchBusy {
            orchestrator.deleteBot(bot.id)
            scheduleRepository.deleteForBot(bot.id)
            wakeup?.request()
            _deleted.tryEmit(Unit)
            "已删除 ${bot.name}"
        }
    }

    fun rollback(hash: String) {
        val bot = _uiState.value.bot ?: return
        launchBusy {
            val result = memoryManager.rollbackToCommit(bot.id, hash)
            refreshMemory(bot.id)
            "已恢复到 ${hash.take(8)}，恢复前快照 ${result.backupHash.take(8)} 已保留"
        }
    }

    fun exportBot() {
        val bot = _uiState.value.bot ?: return
        launchBusy {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            val dir = File(base, "bot-exports").apply { mkdirs() }
            val safeName = bot.name.replace(Regex("[^\\p{L}\\p{N}_-]"), "_")
            val target = File(dir, "$safeName-${System.currentTimeMillis()}.zip")
            packageService.exportBot(bot.id, target)
            "已导出到 ${target.absolutePath}"
        }
    }

    suspend fun readMemoryDocument(botId: String, path: String) = memoryManager.readDocument(botId, path)

    suspend fun memoryDiff(botId: String, hash: String) = memoryManager.getCommitDiff(botId, hash)

    suspend fun saveMemoryDocument(botId: String, document: top.wkbin.taixu.core.model.bot.BotMemoryDocument, content: String) {
        check(boundBotId == botId && _uiState.value.bot?.id == botId) { "Bot 已切换，未保存" }
        memoryManager.writeMemory(botId, document.path, content, "memory: 编辑 ${document.path}", document.revision)
        refreshMemory(botId)
    }

    fun addSchedule(cron: String, instruction: String, groupId: String?) {
        val bot = _uiState.value.bot ?: return
        if (instruction.isBlank()) {
            emit("请填写到点执行的指令")
            return
        }
        val next = runCatching { CronSchedule.parse(cron).nextAfter(System.currentTimeMillis()) }
            .getOrElse {
                emit("Cron 表达式无效：${it.message}")
                return
            } ?: run {
            emit("Cron 表达式在未来两年内没有触发时间")
            return
        }
        launchBusy {
            scheduleRepository.saveSchedule(
                BotSchedule(
                    id = UUID.randomUUID().toString(),
                    botId = bot.id,
                    groupId = groupId,
                    cron = cron.trim(),
                    instruction = instruction.trim(),
                    enabled = true,
                    nextRunAt = next,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            wakeup?.request()
            "已添加定时任务"
        }
    }

    fun setScheduleEnabled(schedule: BotSchedule, enabled: Boolean) {
        launchBusy {
            val next = if (enabled) {
                CronSchedule.parse(schedule.cron).nextAfter(System.currentTimeMillis()) ?: Long.MAX_VALUE
            } else {
                schedule.nextRunAt
            }
            scheduleRepository.saveSchedule(schedule.copy(enabled = enabled, nextRunAt = next))
            wakeup?.request()
            if (enabled) "已启用定时任务" else "已停用定时任务"
        }
    }

    fun deleteSchedule(id: String) {
        launchBusy {
            scheduleRepository.deleteSchedule(id)
            wakeup?.request()
            "已删除定时任务"
        }
    }

    private suspend fun refreshMemory(botId: String) {
        val files = runCatching { memoryManager.listFiles(botId) }.getOrDefault(emptyList())
        val commits = runCatching { memoryManager.getCommitHistory(botId) }.getOrDefault(emptyList())
        _uiState.update { it.copy(memoryFiles = files, commits = commits) }
    }

    private fun launchBusy(block: suspend () -> String) {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            runCatching { block() }
                .onSuccess { emit(it) }
                .onFailure { emit("操作失败：${it.message}") }
            _uiState.update { it.copy(busy = false) }
        }
    }

    private fun emit(message: String) {
        _notices.tryEmit(message)
    }
}
