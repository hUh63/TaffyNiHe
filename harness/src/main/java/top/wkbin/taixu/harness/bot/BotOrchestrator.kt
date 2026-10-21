package top.wkbin.taixu.harness.bot

import kotlinx.coroutines.flow.first

import top.wkbin.taixu.core.common.logging.AppLogger
import top.wkbin.taixu.core.database.HarnessSessionEntity
import top.wkbin.taixu.core.database.HarnessSessionRepository
import top.wkbin.taixu.core.database.repository.BotGroupSessionRepository
import top.wkbin.taixu.core.database.repository.GroupChatRepository
import top.wkbin.taixu.core.database.repository.PersonaBotRepository
import top.wkbin.taixu.core.model.ApprovalMode
import top.wkbin.taixu.core.model.bot.Bot
import top.wkbin.taixu.harness.bot.memory.BotMemoryManager
import top.wkbin.taixu.harness.session.PromptSubmission
import top.wkbin.taixu.harness.session.SessionControl
import top.wkbin.taixu.harness.subagent.SubagentLaneResult
import top.wkbin.taixu.harness.subagent.SubagentLaneRunner
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * PersonaBot 生命周期编排：独占会话、模型选择、人格记忆与后台 Lane 委派。
 *
 * 新建会话采用 FULL_ACCESS；已有会话保留执行目标，群聊通过审批面板恢复等待的操作。
 * Bot 生命周期操作另有主控 Bot 身份校验（见 BotManagementPlugin）。
 */
class BotOrchestrator(
    private val botRepository: PersonaBotRepository,
    private val groupRepository: GroupChatRepository,
    private val groupSessionRepository: BotGroupSessionRepository,
    private val sessionRepository: HarnessSessionRepository,
    private val sessionControl: SessionControl,
    private val laneRunner: SubagentLaneRunner,
    private val memoryManager: BotMemoryManager,
    private val logger: AppLogger,
    private val modelManager: BotModelManager,
    private val assignmentRepository: top.wkbin.taixu.core.database.repository.BotAssignmentRepository? = null,
) {
    private val groupSessionLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun createBot(
        name: String,
        role: String,
        systemPrompt: String,
        modelId: String? = null,
        isMainBot: Boolean = false,
        colorHex: String = "#3D5AFE",
        groupId: String? = null,
        avatarSeed: String? = null,
        modelVariant: String? = null,
        allowModelSelection: Boolean = false,
    ): Bot {
        val cleanName = name.trim().take(40)
        val cleanRole = role.trim().take(40)
        require(cleanName.isNotBlank()) { "Bot 名称不能为空" }
        require(cleanRole.isNotBlank()) { "Bot 职责不能为空" }
        require(systemPrompt.isNotBlank()) { "Bot 人格设定不能为空" }
        val selection = modelManager.validate(modelId, modelVariant)

        val existing = botRepository.listBots()
        val main = isMainBot || existing.none { it.isMainBot }
        val botId = UUID.randomUUID().toString()
        val sessionId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val bot = Bot(
            id = botId,
            name = cleanName,
            role = cleanRole,
            systemPrompt = systemPrompt.trim(),
            avatarSeed = avatarSeed?.takeIf { it.isNotBlank() } ?: cleanName,
            modelId = selection.first,
            modelVariant = selection.second,
            allowModelSelection = allowModelSelection,
            providerId = null,
            sessionId = sessionId,
            isMainBot = main,
            colorHex = colorHex,
            createdAt = now,
            updatedAt = now,
        )

        try {
            sessionRepository.upsert(sessionEntity(bot, now))
            memoryManager.initRepo(botId, cleanName)
            memoryManager.writeMemory(botId, BotMemoryManager.PERSONA, bot.systemPrompt, "feat: 写入初始 Persona")
            botRepository.saveBot(bot)
            if (groupId != null) {
                val order = groupRepository.getMembers(groupId).size
                groupRepository.addMember(groupId, botId, order)
            }
            logger.i("[BotOrchestrator] 已孵化 Bot: $cleanName ($botId), session=$sessionId")
            return bot
        } catch (failure: Throwable) {
            runCatching { botRepository.deleteBot(botId) }
            runCatching { sessionRepository.deleteSession(sessionId) }
            runCatching { memoryManager.deleteMemory(botId) }
            throw failure
        }
    }

    suspend fun updateBot(
        botId: String,
        name: String,
        role: String,
        systemPrompt: String,
        modelId: String?,
        isMainBot: Boolean,
        avatarSeed: String? = null,
        modelVariant: String? = null,
        allowModelSelection: Boolean? = null,
    ): Bot = modelManager.withBotLock(botId) {
        val current = requireNotNull(botRepository.getBot(botId)) { "Bot 不存在" }
        val selection = modelManager.validate(modelId, modelVariant)
        val cleanName = name.trim().take(40).ifBlank { current.name }
        val cleanRole = role.trim().take(40).ifBlank { current.role }
        val keepMain = if (!isMainBot && current.isMainBot) {
            !botRepository.listBots().any { it.id != botId && it.isMainBot }
        } else {
            isMainBot
        }
        val updated = current.copy(
            name = cleanName,
            role = cleanRole,
            systemPrompt = systemPrompt.trim().ifBlank { current.systemPrompt },
            modelId = selection.first,
            modelVariant = selection.second,
            allowModelSelection = allowModelSelection ?: current.allowModelSelection,
            isMainBot = keepMain,
            avatarSeed = avatarSeed?.takeIf { it.isNotBlank() } ?: current.avatarSeed,
            updatedAt = System.currentTimeMillis(),
        )
        botRepository.saveBot(updated)
        sessionRepository.rename(updated.sessionId, sessionTitle(updated), updated.updatedAt)
        modelManager.syncSessions(updated)
        if (updated.systemPrompt != current.systemPrompt) {
            memoryManager.writeMemory(botId, BotMemoryManager.PERSONA, updated.systemPrompt, "feat: 更新 Persona 设定")
        }
        updated
    }

    suspend fun deleteBot(botId: String) {
        val bot = botRepository.getBot(botId) ?: return
        val adminGroups = groupRepository.countGroupsAsAdmin(botId)
        check(adminGroups == 0) { "该 Bot 仍是 $adminGroups 个群聊的管理员，请先转移管理员" }
        assignmentRepository?.assignmentsForBot(botId)?.forEach { task ->
            assignmentRepository.cancelAssignment(task.id)
            task.sessionId?.let { sessionControl.deleteSession(it) }
        }
        runCatching { sessionControl.deleteSession(bot.sessionId) }
            .onFailure { logger.w("[BotOrchestrator] 删除 Bot 会话失败: ${it.message}", it) }
        runCatching { groupSessionRepository.listSessionIdsForBot(botId) }.getOrDefault(emptyList())
            .forEach { sessionId ->
                runCatching { sessionControl.deleteSession(sessionId) }
                    .onFailure { logger.w("[BotOrchestrator] 删除群维度会话失败: ${it.message}", it) }
            }
        botRepository.deleteBot(botId)
        memoryManager.deleteMemory(botId)
        if (bot.isMainBot) {
            botRepository.listBots().firstOrNull()?.let { promote ->
                botRepository.saveBot(promote.copy(isMainBot = true, updatedAt = System.currentTimeMillis()))
            }
        }
        logger.i("[BotOrchestrator] 已删除 Bot: ${bot.name}")
    }

    /** 独占会话可能被用户在会话列表里删除；发言前自愈，避免 SESSION_NOT_FOUND。 */
    suspend fun ensureSession(bot: Bot) = modelManager.withBotLock(bot.id) {
        val current = requireNotNull(botRepository.getBot(bot.id)) { "Bot 不存在" }
        ensureSessionRow(current, current.sessionId, sessionTitle(current))
    }

    /**
     * 群维度会话：同一 Bot 在不同群使用独立 Harness 会话，历史、上下文与 Lane 互不污染。
     * 映射缺失时按需创建；同一 (bot, group) 只会创建一次。
     */
    suspend fun ensureGroupSession(bot: Bot, groupId: String): String = modelManager.withBotLock(bot.id) {
        ensureGroupSessionLocked(requireNotNull(botRepository.getBot(bot.id)) { "Bot 不存在" }, groupId)
    }

    private suspend fun ensureGroupSessionLocked(bot: Bot, groupId: String): String =
        groupSessionLocks.computeIfAbsent("${bot.id}:$groupId") { Mutex() }.withLock {
        val existing = groupSessionRepository.sessionIdFor(bot.id, groupId)
        val title = groupSessionTitle(bot, groupRepository.getGroup(groupId)?.name)
        if (existing != null && sessionRepository.findById(existing) != null) {
            ensureSessionRow(bot, existing, title)
            return@withLock existing
        }
        // 修复旧版本留下的悬空映射；重建必须使用新 ID，不能重新匹配旧诊断日志。
        if (existing != null) sessionControl.deleteSession(existing)
        val sessionId = UUID.randomUUID().toString()
        sessionRepository.upsert(sessionEntity(bot, System.currentTimeMillis(), sessionId, title))
        groupSessionRepository.save(bot.id, groupId, sessionId)
        logger.i("[BotOrchestrator] 创建群维度 Bot 会话: ${bot.name} @ $groupId ($sessionId)")
        sessionId
    }

    suspend fun groupSessionId(botId: String, groupId: String): String? =
        groupSessionRepository.sessionIdFor(botId, groupId)

    suspend fun groupIdForSession(sessionId: String): String? =
        groupSessionRepository.groupIdForSession(sessionId)

    /** 解散群聊前移除各 Bot 的群维度会话，避免留下无主会话。 */
    suspend fun deleteGroupSessions(groupId: String) {
        val independent = assignmentRepository?.observeForGroup(groupId)?.first().orEmpty()
            .filter { it.independent }.mapNotNull { it.sessionId }
        (groupSessionRepository.listSessionIdsForGroup(groupId) + independent).distinct().forEach { sessionId ->
            sessionControl.deleteSession(sessionId)
        }
    }

    private suspend fun ensureSessionRow(bot: Bot, sessionId: String, title: String) {
        val existing = sessionRepository.findById(sessionId)
        if (existing == null) {
            sessionRepository.upsert(
                sessionEntity(bot, System.currentTimeMillis(), sessionId, title),
            )
            logger.w("[BotOrchestrator] Bot 会话缺失，已重建: ${bot.name} ($sessionId)")
            return
        }
        val now = System.currentTimeMillis()
        // 群聊自动执行没有审批 UI；旧会话可能仍是 ASSISTED，发言前统一升级为完全访问。
        val needsFullAccess = existing.approvalMode != ApprovalMode.FULL_ACCESS.id
        // Existing sessions own their execution target. Never silently move resumed work to another environment.
        if (needsFullAccess) {
            sessionRepository.upsert(
                existing.copy(
                    approvalMode = ApprovalMode.FULL_ACCESS.id,
                    updatedAt = now,
                ),
            )
            logger.i("[BotOrchestrator] Bot 会话已启用完全访问（免审批）: ${bot.name} ($sessionId)")
        }
        if (existing.modelId != bot.modelId || existing.modelVariant != bot.modelVariant) {
            sessionRepository.setModelSelection(sessionId, bot.modelId, bot.modelVariant, now)
        }
    }

    suspend fun sendMessageToBot(botId: String, text: String): PromptSubmission {
        val bot = requireNotNull(botRepository.getBot(botId)) { "Bot $botId 不存在" }
        ensureSession(bot)
        return sessionControl.submit(bot.sessionId, text)
    }

    suspend fun dispatchSubtask(
        botId: String,
        taskPrompt: String,
        writePaths: List<String> = emptyList(),
        extraContext: String = "",
        laneName: String? = null,
        groupId: String? = null,
    ): SubagentLaneResult {
        val bot = requireNotNull(botRepository.getBot(botId)) { "Bot $botId 未就绪" }
        val sessionId = if (groupId != null) {
            ensureGroupSession(bot, groupId)
        } else {
            ensureSession(bot)
            bot.sessionId
        }
        val memoryContext = memoryManager.assembleContext(botId)
        val prompt = buildString {
            if (memoryContext.isNotBlank()) appendLine(memoryContext).appendLine()
            if (extraContext.isNotBlank()) appendLine(extraContext).appendLine()
            appendLine("【当前指派的专项工作】")
            appendLine(taskPrompt)
        }
        val workspace = sessionRepository.findById(sessionId)?.workspace.orEmpty()
        val resolvedLaneName = laneName
            ?: "subagent:bot-${bot.id.take(8)}:${UUID.randomUUID().toString().take(8)}"
        return laneRunner.run(
            sessionId = sessionId,
            laneName = resolvedLaneName,
            prompt = prompt,
            workspace = workspace,
            modelId = bot.modelId,
            modelVariant = bot.modelVariant,
            writePaths = writePaths,
        )
    }

    suspend fun findBotBySession(sessionId: String): Bot? = botRepository.getBotBySessionId(sessionId)

    private fun sessionTitle(bot: Bot): String = "🤖 ${bot.name}（${bot.role}）"

    private fun groupSessionTitle(bot: Bot, groupName: String?): String =
        "🤖 ${bot.name}（${bot.role}）· ${groupName ?: "群聊"}"

    private fun sessionEntity(
        bot: Bot,
        now: Long,
        sessionId: String = bot.sessionId,
        title: String = sessionTitle(bot),
    ) = HarnessSessionEntity(
        id = sessionId,
        title = title,
        createdAt = now,
        updatedAt = now,
        modelId = bot.modelId,
        modelVariant = bot.modelVariant,
        workspace = "",
        projectType = "",
        approvalMode = ApprovalMode.FULL_ACCESS.id,
        executionTargetJson = "unbound",
    )
}
