package top.wkbin.taixu.harness.groupchat

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.wkbin.taixu.core.common.logging.AppLogger
import top.wkbin.taixu.core.database.AppDatabase
import top.wkbin.taixu.core.database.BotEntity
import top.wkbin.taixu.core.database.GroupChatEntity
import top.wkbin.taixu.core.database.RoomHarnessRuntimeRepository
import top.wkbin.taixu.core.database.repository.RoomGroupChatRepository
import top.wkbin.taixu.core.database.repository.RoomPersonaBotRepository
import top.wkbin.taixu.core.database.task.AgentTaskEntity
import top.wkbin.taixu.core.database.task.AgentTaskStatus
import top.wkbin.taixu.core.database.task.RoomAgentTaskRepository
import top.wkbin.taixu.core.model.SessionRunState
import top.wkbin.taixu.core.model.bot.*
import top.wkbin.taixu.harness.*
import top.wkbin.taixu.harness.queue.PromptQueue
import top.wkbin.taixu.harness.session.PromptSubmission
import top.wkbin.taixu.harness.session.SessionControl
import top.wkbin.taixu.harness.task.TaskDeliveryReport

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupReplyTaskOwnershipTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val groups = RoomGroupChatRepository(db.groupChatDao(), Json)
    private val tasks = RoomAgentTaskRepository(db.agentTaskDao())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val control = Control()
    private val tracker = GroupReplyTaskTracker(tasks, RoomHarnessRuntimeRepository(db.harnessRuntimeDao()), control)
    private val responder = GroupBotResponder(
        groups, { _, _ -> "session" }, control, AppLogger(context) { it }, MutableStateFlow(emptyMap()),
        lateReplyScope = scope, workGraceMs = 500, taskTracker = tracker,
    )

    @After fun close() = runBlocking {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        db.close()
    }

    private suspend fun seed(): Bot {
        db.personaBotDao().upsert(BotEntity("bot", "Bot", "role", "persona", "seed", null, null, "dm", false, "#123456", 1, 1))
        db.groupChatDao().upsertGroup(GroupChatEntity("group", "Team", "bot", "", 1))
        groups.addMember("group", "bot", 0)
        return RoomPersonaBotRepository(db.personaBotDao()).getBot("bot")!!
    }

    @Test fun `queued reply ignores its predecessor and finishes from its own delivery`() = runBlocking {
        val bot = seed()
        val group = groups.getGroup("group")!!
        val firstLate = CompletableDeferred<String>()
        val first = withTimeout(5_000) {
            responder.respond(group, bot, "first", "", listOf(bot), emptyList(), null,
                onLateReply = { firstLate.complete(it) })
        }
        assertTrue(first.working)
        assertEquals("task-1", groups.watchingLateReplies().single().taskId)
        val secondLate = CompletableDeferred<String>()
        val second = async(Dispatchers.Default) {
            responder.respond(group, bot, "second", "", listOf(bot), emptyList(), null,
                onLateReply = { secondLate.complete(it) })
        }
        withTimeout(5_000) { control.submissions.first { it == 2 } }
        control.finish("task-1", "FIRST_RESULT")
        assertEquals("FIRST_RESULT", withTimeout(5_000) { firstLate.await() })
        delay(100)
        assertFalse("A queued task must not settle when its predecessor finishes", second.isCompleted)
        assertFalse(secondLate.isCompleted)
        control.startSecond()
        val secondResult = withTimeout(5_000) { second.await() }
        assertTrue(secondResult.working)
        control.finish("task-2", "SECOND_RESULT")
        assertEquals("SECOND_RESULT", withTimeout(5_000) { secondLate.await() })
        val rows = groups.observeMessages("group").first()
        assertEquals(setOf("FIRST_RESULT", "SECOND_RESULT"), rows.map { it.content }.toSet())
        assertTrue(rows.none { it.isStreaming })
    }

    @Test fun `cold recovery reads durable history even when live state is empty`() = runBlocking {
        val bot = seed()
        val now = System.currentTimeMillis()
        tasks.upsert(task("task-1", AgentTaskStatus.RUNNING, now - 500))
        control.finish("task-1", "DURABLE_RESULT")
        control.messages.value = emptyList()
        control.sessionRunStates.value = emptyMap()
        groups.postMessage(GroupMessage("placeholder", "group", bot.id, bot.name, GroupSenderType.BOT, "", now - 1_000, true))
        groups.insertLateReply(GroupLateReply("watch", "group", bot.id, bot.name, "session", "placeholder",
            createdAt = now, status = GroupLateReplyStatus.WATCHING, taskId = "task-1"))
        val restored = CompletableDeferred<String>()
        responder.recoverLateReplies { _, text -> restored.complete(text) }
        assertEquals("DURABLE_RESULT", withTimeout(5_000) { restored.await() })
        val row = groups.observeMessages("group").first().single()
        assertEquals("DURABLE_RESULT", row.content)
        assertFalse(row.isStreaming)
        assertTrue(control.prepared)
        withTimeout(5_000) {
            while (groups.findLateReply("watch")?.status != GroupLateReplyStatus.DONE) delay(10)
        }
    }

    private fun task(id: String, status: String, startedAt: Long?) = AgentTaskEntity(
        id = id, sessionId = "session", title = id, description = id, status = status,
        createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(), startedAt = startedAt,
    )

    private inner class Control : SessionControl {
        override val currentSessionId = MutableStateFlow("session")
        override val sessionRunStates = MutableStateFlow<Map<String, SessionRunState>>(emptyMap())
        override val sessionStatuses = MutableStateFlow<Map<String, String>>(emptyMap())
        val messages = MutableStateFlow<List<HarnessMessage>>(emptyList())
        val submissions = MutableStateFlow(0)
        private var durable = emptyList<HarnessMessage>()
        @Volatile var prepared = false
        override fun messagesForSession(sessionId: String) = messages
        // Deliberately keep the live flow cold: durable reads must drive recovered completion.
        override suspend fun prepareRemoteSession(sessionId: String): List<HarnessMessage> {
            prepared = true
            return durable
        }
        override suspend fun persistedMessages(sessionId: String) = durable
        override suspend fun deleteSession(id: String) = Unit
        override suspend fun submit(sessionId: String, text: String, imageUrls: List<String>, queue: PromptQueue): PromptSubmission {
            val count = submissions.value + 1
            val now = System.currentTimeMillis()
            tasks.upsert(task("task-$count", if (count == 1) AgentTaskStatus.RUNNING else AgentTaskStatus.QUEUED,
                if (count == 1) now else null))
            if (count == 1) {
                messages.value = listOf(AssistantText("working-1", now, "working", totalMs = 1))
                durable = messages.value
                sessionRunStates.value = mapOf("session" to SessionRunState.RUNNING)
            }
            submissions.value = count
            return PromptSubmission.Accepted(if (count == 1) PromptSubmission.Disposition.STARTED else PromptSubmission.Disposition.QUEUED, "task-$count")
        }
        suspend fun startSecond() {
            val now = System.currentTimeMillis()
            messages.value += AssistantText("working-2", now, "second working", totalMs = 1)
            durable = messages.value
            tasks.upsert(task("task-2", AgentTaskStatus.RUNNING, now))
        }
        suspend fun finish(id: String, text: String) {
            val now = System.currentTimeMillis()
            val final = AssistantText("final-$id", now, text, totalMs = 1)
            val delivery = TaskDelivery("delivery-$id", now, TaskDeliveryReport(
                id, "session", "operation-$id", null, final.id, final.id, emptyList(), emptyList(), true, emptyList(), now,
            ))
            durable = durable + final + delivery
            // A successor may already be running; its output must not replace this task's answer.
            messages.value = durable + AssistantText("unrelated-$id", now + 1, "UNRELATED_RESULT", totalMs = 1)
            sessionRunStates.value = mapOf("session" to SessionRunState.RUNNING)
            tasks.upsert(requireNotNull(tasks.find(id)).copy(status = AgentTaskStatus.COMPLETED, completedAt = now))
        }
        override fun send(text: String, targetSessionId: String?, imageUrls: List<String>) = Unit
        override fun steer(text: String, targetSessionId: String?, imageUrls: List<String>) = Unit
        override fun followUp(text: String, targetSessionId: String?, imageUrls: List<String>) = Unit
        override fun cancel(targetSessionId: String?) { sessionRunStates.value = mapOf("session" to SessionRunState.IDLE) }
        override fun resolveApproval(requestId: String, approved: Boolean, rememberForSession: Boolean) = Unit
        override fun resolveQuestion(requestId: String, answersJson: String) = Unit
    }
}
