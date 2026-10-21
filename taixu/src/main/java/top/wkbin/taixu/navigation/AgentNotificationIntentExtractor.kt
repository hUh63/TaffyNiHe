package top.wkbin.taixu.navigation

import android.content.Intent
import top.wkbin.taixu.core.common.navigation.AppNavigationTarget
import top.wkbin.taixu.core.database.repository.BotGroupSessionRepository
import top.wkbin.taixu.service.AgentForegroundService

/** 从任务/会话通知 Intent 中解析深链目标（自动按会话类型定位群聊或独立对话）。 */
object AgentNotificationIntentExtractor {
    suspend fun extractTarget(
        intent: Intent?,
        sessions: BotGroupSessionRepository?,
    ): AppNavigationTarget.AgentSession? {
        if (intent == null) return null
        val uri = intent.data
        if (uri?.scheme != "taixu" || uri.authority != "agent-notification") return null
        val sessionId = intent.getStringExtra(AgentForegroundService.EXTRA_SESSION_ID)
            ?: uri.pathSegments.firstOrNull()?.takeIf { it != "all" }
        val groupId = if (!sessionId.isNullOrBlank()) sessions?.groupIdForSession(sessionId) else null
        return AppNavigationTarget.AgentSession(sessionId, groupId)
    }
}
