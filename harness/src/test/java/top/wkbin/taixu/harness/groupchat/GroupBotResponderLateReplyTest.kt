package top.wkbin.taixu.harness.groupchat

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.wkbin.taixu.core.common.logging.AppLogger
import top.wkbin.taixu.core.database.AppDatabase
import top.wkbin.taixu.core.database.BotEntity
import top.wkbin.taixu.core.database.GroupChatEntity
import top.wkbin.taixu.core.database.GroupMemberEntity
import top.wkbin.taixu.core.database.repository.RoomGroupChatRepository
import top.wkbin.taixu.core.database.repository.RoomPersonaBotRepository
import top.wkbin.taixu.core.model.SessionRunState
import top.wkbin.taixu.core.model.bot.Bot
import top.wkbin.taixu.core.model.bot.GroupLateReplyStatus
import top.wkbin.taixu.core.model.bot.GroupSenderType
import top.wkbin.taixu.harness.AssistantText
import top.wkbin.taixu.harness.HarnessMessage
import top.wkbin.taixu.harness.queue.PromptQueue
import top.wkbin.taixu.harness.session.PromptSubmission
import top.wkbin.taixu.harness.session.SessionControl

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupBotResponderLateReplyTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val groups = RoomGroupChatRepository(db.groupChatDao(), Json)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val logger = AppLogger(context) { it }
    private val control = FakeControl()
    private val liveReplies = MutableStateFlow<Map<String, GroupReplyProgress>>(emptyMap())

    @After fun close() = runBlocking {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        db.close()
    }

    private suspend fun seed(): Bot {
        db.personaBotDao().upsert(
            BotEntity("bot", "Bot", "role", "persona", "seed", null, null, "dm", false, "#123456", 1, 1),
        )
        db.groupChatDao().upsertGroup(GroupChatEntity("group", "Team", "bot", "", 1))
        db.groupChatDao().addMember(GroupMemberEntity("group", "bot", 0, 1))
        return RoomPersonaBotRepository(db.personaBotDao()).getBot("bot")!!
    }

    @Test fun `a working turn yields and posts its late result`() = runBlocking {
        val bot = seed()
        val group = groups.getGroup("group")!!
        val responder = GroupBotResponder(
            groups, { _, _ -> "session" }, control, logger, liveReplies,
            lateReplyScope = scope, workGraceMs = 80,
        )
        val late = CompletableDeferred<String>()
        val result = responder.respond(
            group, bot, "instruction", "", listOf(bot), emptyList(), null,
            onLateReply = { late.complete(it) },
        )
        assertTrue(result.working)

        control.finish("final text")
        withTimeout(5_000) { late.await() }

        val message = withTimeout(5_000) {
            groups.observeMessages("group").first { rows ->
                rows.any { it.senderType == GroupSenderType.BOT && !it.isStreaming }
            }
        }.single { it.senderType == GroupSenderType.BOT }
        assertEquals("final text", message.content)
        assertFalse(message.isStreaming)
    }

    @Test fun `waiting for approval yields and posts the result later`() = runBlocking {
        val bot = seed()
        val group = groups.getGroup("group")!!
        control.approvalOnSubmit = true
        val responder = GroupBotResponder(
            groups, { _, _ -> "session" }, control, logger, MutableStateFlow(emptyMap()),
            lateReplyScope = scope, workGraceMs = 50,
        )
        val late = CompletableDeferred<String>()
        val result = responder.respond(
            group, bot, "instruction", "", listOf(bot), emptyList(), null,
            onLateReply = { late.complete(it) },
        )
        assertTrue(result.working)

        control.finish("approved result")
        withTimeout(5_000) { late.await() }

        val message = withTimeout(5_000) {
            groups.observeMessages("group").first { rows ->
                rows.any { it.senderType == GroupSenderType.BOT && !it.isStreaming }
            }
        }.single { it.senderType == GroupSenderType.BOT }
        assertEquals("approved result", message.content)
    }

    @Test fun `a silent pass removes the placeholder and reports passed`() = runBlocking {
        val bot = seed()
        val group = groups.getGroup("group")!!
        control.replyOnSubmit = "[PASS]"
        val responder = GroupBotResponder(
            groups, { _, _ -> "session" }, control, logger, MutableStateFlow(emptyMap()),
        )
        val result = responder.respond(
            group, bot, "instruction", "", listOf(bot), emptyList(), null, silentPass = true,
        )
        assertTrue(result.passed)
        assertFalse(result.working)
        assertTrue(groups.observeMessages("group").first().none { it.senderType == GroupSenderType.BOT })
    }

    @Test fun `a watching late reply is recovered after restart`() = runBlocking {
        val bot = seed()
        val group = groups.getGroup("group")!!
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val first = GroupBotResponder(
                groups, { _, _ -> "session" }, control, logger, MutableStateFlow(emptyMap()),
                lateReplyScope = firstScope, workGraceMs = 500,
            )
            val result = withTimeout(5_000) {
                first.respond(
                    group, bot, "instruction", "", listOf(bot), emptyList(), null,
                    lateReplyCallerId = "caller", onLateReply = {},
                )
            }
            assertTrue(result.working)
        } finally {
            firstScope.cancel()
        }
        val watching = groups.watchingLateReplies()
        assertEquals(1, watching.size)

        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val recovered = CompletableDeferred<String>()
            val second = GroupBotResponder(
                groups, { _, _ -> "session" }, control, logger, MutableStateFlow(emptyMap()),
                lateReplyScope = secondScope, workGraceMs = 50,
            )
            second.recoverLateReplies { _, text -> recovered.complete(text) }
            control.finish("recovered text")
            withTimeout(5_000) { recovered.await() }

            val message = withTimeout(5_000) {
                groups.observeMessages("group").first { rows ->
                    rows.any { it.senderType == GroupSenderType.BOT && !it.isStreaming }
                }
            }.single { it.senderType == GroupSenderType.BOT }
            assertEquals("recovered text", message.content)
            assertEquals(GroupLateReplyStatus.DONE, groups.findLateReply(watching.single().id)?.status)
        } finally {
            secondScope.cancel()
        }
    }

    private class FakeControl : SessionControl {
        override val currentSessionId = MutableStateFlow("session")
        override val sessionRunStates = MutableStateFlow<Map<String, SessionRunState>>(emptyMap())
        override val sessionStatuses = MutableStateFlow<Map<String, String>>(emptyMap())
        private val messages = MutableStateFlow<List<HarnessMessage>>(emptyList())
        @Volatile var approvalOnSubmit = false
        @Volatile var replyOnSubmit: String? = null

        override fun messagesForSession(sessionId: String) = messages
        override suspend fun prepareRemoteSession(sessionId: String) = messages.value
        override suspend fun persistedMessages(sessionId: String) = messages.value
        override suspend fun deleteSession(id: String) = Unit

        override suspend fun submit(
            sessionId: String,
            text: String,
            imageUrls: List<String>,
            queue: PromptQueue,
        ): PromptSubmission {
            val reply = replyOnSubmit
            if (reply != null) {
                messages.value = listOf(
                    AssistantText("final", System.currentTimeMillis(), reply, totalMs = 1),
                )
                sessionRunStates.value = mapOf(sessionId to SessionRunState.COMPLETED)
                return PromptSubmission.Accepted(PromptSubmission.Disposition.STARTED, "native-task")
            }
            sessionRunStates.value = mapOf(
                sessionId to if (approvalOnSubmit) SessionRunState.WAITING_APPROVAL else SessionRunState.RUNNING,
            )
            messages.value = listOf(
                AssistantText("working", System.currentTimeMillis(), "working on it", totalMs = 1),
            )
            return PromptSubmission.Accepted(PromptSubmission.Disposition.STARTED, "native-task")
        }

        fun finish(text: String) {
            messages.value = messages.value + AssistantText("final", System.currentTimeMillis(), text, totalMs = 1)
            sessionRunStates.value = mapOf("session" to SessionRunState.COMPLETED)
        }

        override fun send(text: String, targetSessionId: String?, imageUrls: List<String>) = Unit
        override fun steer(text: String, targetSessionId: String?, imageUrls: List<String>) = Unit
        override fun followUp(text: String, targetSessionId: String?, imageUrls: List<String>) = Unit
        override fun cancel(targetSessionId: String?) {
            sessionRunStates.value = mapOf("session" to SessionRunState.IDLE)
        }
        override fun resolveApproval(requestId: String, approved: Boolean, rememberForSession: Boolean) = Unit
        override fun resolveQuestion(requestId: String, answersJson: String) = Unit
    }
}
