package top.wkbin.taixu.harness.groupchat

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
import top.wkbin.taixu.core.database.repository.RoomGroupChatRepository
import top.wkbin.taixu.core.database.repository.GroupChatRepository
import top.wkbin.taixu.core.model.bot.Bot
import top.wkbin.taixu.core.model.bot.GroupChat
import top.wkbin.taixu.core.model.bot.GroupMessage
import top.wkbin.taixu.core.model.bot.GroupRound
import top.wkbin.taixu.core.model.bot.GroupRoundSlot
import top.wkbin.taixu.core.model.bot.GroupRoundStatus
import top.wkbin.taixu.core.model.bot.GroupSenderType
import top.wkbin.taixu.core.model.bot.GroupSlotStatus

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupRoundCoordinatorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val groups = RoomGroupChatRepository(db.groupChatDao(), Json)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val logger = AppLogger(context) { it }
    private val queue = GroupReplyQueue(scope)
    private val recorded = mutableListOf<String>()
    private val histories = ConcurrentHashMap<String, String>()
    private val triggers = ConcurrentHashMap<String, String>()
    private val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val replies = mutableMapOf<String, String>()
    private val workingBots = ConcurrentHashMap.newKeySet<String>()
    private val skipSubmit = ConcurrentHashMap.newKeySet<String>()
    private val lateCallbacks = ConcurrentHashMap<String, suspend (String) -> Unit>()
    private val inside = AtomicInteger()
    @Volatile private var maxInside = 0

    private val responder = GroupRoundResponder { group, bot, trigger, history, _, _, _, onSubmitted, onLateReply ->
        val nowInside = inside.incrementAndGet()
        synchronized(recorded) {
            recorded += bot.name
            histories[bot.name] = history
            triggers[bot.name] = trigger
            maxInside = maxOf(maxInside, nowInside)
        }
        try {
            if (bot.name !in skipSubmit) onSubmitted()
            if (bot.name in workingBots) {
                lateCallbacks[bot.name] = onLateReply
                GroupTurnOutcome("", failed = false, working = true)
            } else {
                val text = synchronized(replies) { replies[bot.name] } ?: when (bot.name) {
                    "A" -> "@C 你补充"
                    "B" -> "[PASS]"
                    else -> "done ${bot.name}"
                }
                gates[bot.name]?.await()
                if (!isGroupPass(text)) {
                    groups.postMessage(
                        GroupMessage(
                            id = UUID.randomUUID().toString(),
                            groupId = group.id,
                            senderId = bot.id,
                            senderName = bot.name,
                            senderType = GroupSenderType.BOT,
                            content = text,
                            timestamp = System.currentTimeMillis(),
                        ),
                    )
                }
                GroupTurnOutcome(text, failed = false, passed = isGroupPass(text))
            }
        } finally {
            inside.decrementAndGet()
        }
    }

    @After fun close() {
        scope.cancel()
        db.close()
    }

    private suspend fun seed(): GroupChat {
        val chief = BotEntity("chief", "Chief", "admin", "persona", "seed", null, null, "chief-dm", true, "#123456", 1, 1)
        db.personaBotDao().upsert(chief)
        listOf("a" to "A", "b" to "B", "c" to "C").forEach { (id, name) ->
            db.personaBotDao().upsert(
                chief.copy(id = id, name = name, sessionId = "$id-dm", isMainBot = false),
            )
        }
        db.groupChatDao().upsertGroup(GroupChatEntity("group", "Team", "chief", "", 1))
        listOf("chief", "a", "b", "c").forEachIndexed { index, id -> groups.addMember("group", id, index) }
        return groups.getGroup("group")!!
    }

    private suspend fun members(): List<Bot> = groups.getMembers("group")
    private fun member(all: List<Bot>, name: String): Bot = all.first { it.name == name }
    private fun coordinator() = GroupRoundCoordinator(groups, queue, responder, scope, logger)

    @Test fun `round speaks in order, skips pass, and follows mentions`() = runBlocking {
        val group = seed()
        val all = members()
        groups.postMessage(
            GroupMessage(
                id = "user-1", groupId = "group", senderId = "user", senderName = "我",
                senderType = GroupSenderType.USER, content = "hello", timestamp = 1,
            ),
        )
        coordinator().start(group, listOf(member(all, "A"), member(all, "B")), "hello")
        withTimeout(5_000) { while (synchronized(recorded) { recorded.size } < 3) delay(10) }

        assertEquals(listOf("A", "C", "B"), synchronized(recorded) { recorded.toList() })
        assertTrue(histories["C"].orEmpty().contains("@C 你补充"))
        val messages = groups.observeMessages("group").first()
        assertTrue(messages.none { it.senderName == "B" })
        assertTrue(messages.any { it.senderName == "A" && it.content.contains("@C") })
        assertTrue(messages.any { it.senderName == "C" && it.content.contains("done C") })
    }

    @Test fun `a new user message waits for the running round`() = runBlocking {
        val group = seed()
        val all = members()
        val gate = CompletableDeferred<Unit>()
        gates["B"] = gate
        val coordinator = coordinator()
        coordinator.start(group, listOf(member(all, "B")), "first")
        withTimeout(5_000) { while (synchronized(recorded) { recorded.size } < 1) delay(10) }
        coordinator.start(group, listOf(member(all, "B")), "second")
        gate.complete(Unit)
        withTimeout(5_000) { while (synchronized(recorded) { recorded.size } < 2) delay(10) }

        assertEquals(1, maxInside)
        assertEquals(listOf("B", "B"), synchronized(recorded) { recorded.toList() })
    }

    @Test fun `round history includes unseen lines and a small earlier window`() = runBlocking {
        val group = seed()
        val all = members()
        repeat(10) { index ->
            groups.postMessage(
                GroupMessage(
                    id = "old-$index", groupId = "group", senderId = "user", senderName = "我",
                    senderType = GroupSenderType.USER, content = "old-$index", timestamp = index.toLong(),
                ),
            )
        }
        groups.setSeenSeq("group", "a", groups.latestSeq("group"))
        groups.postMessage(
            GroupMessage(
                id = "new", groupId = "group", senderId = "user", senderName = "我",
                senderType = GroupSenderType.USER, content = "new-line", timestamp = 11,
            ),
        )
        replies["A"] = "ack"
        coordinator().start(group, listOf(member(all, "A")), "new-line")
        withTimeout(5_000) { while (synchronized(recorded) { recorded.isEmpty() }) delay(10) }

        val history = histories["A"].orEmpty()
        assertTrue(history.contains("new-line"))
        assertTrue(history.contains("old-9"))
        assertFalse(history.contains("old-0"))
    }

    @Test fun `seen is not advanced when submission never starts`() = runBlocking {
        val group = seed()
        val all = members()
        skipSubmit += "A"
        coordinator().start(group, listOf(member(all, "A")), "hello")
        withTimeout(5_000) { while (synchronized(recorded) { recorded.isEmpty() }) delay(10) }

        assertEquals(0, groups.getSeenSeq("group", "a"))
    }

    @Test fun `a stale running round is cancelled instead of resumed`() = runBlocking {
        val group = seed()
        groups.insertRound(
            GroupRound(
                id = "stale",
                groupId = group.id,
                trigger = "old",
                slots = listOf(GroupRoundSlot("a", "A")),
                status = GroupRoundStatus.RUNNING,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        coordinator().recover()
        withTimeout(5_000) {
            while (groups.findRound("stale")?.status != GroupRoundStatus.CANCELLED) delay(10)
        }
        assertTrue(synchronized(recorded) { recorded.isEmpty() })
    }

    @Test fun `a persisted running round resumes from the first waiting slot`() = runBlocking {
        val group = seed()
        val now = System.currentTimeMillis()
        val round = GroupRound(
            id = "round",
            groupId = group.id,
            trigger = "resume",
            slots = listOf(
                GroupRoundSlot("a", "A", GroupSlotStatus.POSTED),
                GroupRoundSlot("b", "B", GroupSlotStatus.WAITING),
            ),
            status = GroupRoundStatus.RUNNING,
            createdAt = now,
            updatedAt = now,
        )
        groups.insertRound(round)

        coordinator().recover()
        withTimeout(5_000) { while (synchronized(recorded) { recorded.none { it == "B" } }) delay(10) }
        assertEquals(listOf("B"), synchronized(recorded) { recorded.toList() })
        withTimeout(5_000) {
            while (groups.findRound("round")?.status != GroupRoundStatus.DONE) delay(10)
        }
    }

    @Test fun `late reply wakes the caller with a new round`() = runBlocking {
        val group = seed()
        val all = members()
        workingBots += "C"
        coordinator().start(group, listOf(member(all, "A"), member(all, "B")), "hello")
        withTimeout(5_000) { while (synchronized(recorded) { recorded.size } < 3) delay(10) }

        val late = lateCallbacks["C"] ?: error("C did not receive a late callback")
        replies["A"] = "ack"
        late("late done")
        withTimeout(5_000) { while (synchronized(recorded) { recorded.size } < 4) delay(10) }

        assertEquals(listOf("A", "C", "B", "A"), synchronized(recorded) { recorded.toList() })
        assertTrue(triggers["A"].orEmpty().contains("late done"))
        assertTrue(triggers["A"].orEmpty().contains("迟到回复"))
    }

    @Test fun `pass detection accepts bracketed and bare forms`() {
        listOf("", "  ", "[PASS]", "【PASS】", "（pass）", "pass.", "PASS!").forEach {
            assertTrue(it, isGroupPass(it))
        }
        listOf("pass 一下", "我 PASS 了", "请通过").forEach {
            assertFalse(it, isGroupPass(it))
        }
    }

    @Test fun `full user instruction and attachment description survive round persistence and prompt`() = runBlocking {
        val group = seed()
        val all = members()
        val instruction = "x".repeat(2_000) + "\n必须保留尾部验收条件\n【附件】/attachments/last-file.txt"
        val coordinator = coordinator()
        val original = coordinator.newRound(group, listOf(member(all, "B")), instruction)!!
        val prepared = coordinator.withAttachments(original, instruction, listOf("data:image/png;base64,abc"))
        groups.insertRound(prepared)
        assertEquals(instruction, groups.findRound(prepared.id)!!.trigger)
        coordinator.recover()
        withTimeout(5_000) { while (!triggers.containsKey("B")) delay(10) }
        assertTrue(triggers["B"].orEmpty().endsWith(instruction + "\n"))
    }

    @Test fun `failed speaking checkpoint stops execution and recovery runs waiting slot once`() = runBlocking {
        val group = seed()
        val all = members()
        val attempted = CompletableDeferred<Unit>()
        val broken = object : GroupChatRepository by groups {
            override suspend fun updateRoundProgress(round: GroupRound) {
                attempted.complete(Unit)
                throw java.io.IOException("database unavailable")
            }
        }
        GroupRoundCoordinator(broken, queue, responder, scope, logger)
            .start(group, listOf(member(all, "B")), "execute once")
        withTimeout(5_000) { attempted.await() }
        assertTrue(synchronized(recorded) { recorded.isEmpty() })
        assertEquals(GroupSlotStatus.WAITING, groups.runningRounds().single().slots.single().status)
        coordinator().recover()
        withTimeout(5_000) { while (groups.runningRounds().isNotEmpty()) delay(10) }
        assertEquals(listOf("B"), synchronized(recorded) { recorded.toList() })
    }

    @Test fun `failed round creation cannot start unpersisted work`() = runBlocking {
        val group = seed()
        val all = members()
        val broken = object : GroupChatRepository by groups {
            override suspend fun insertRound(round: GroupRound) { throw java.io.IOException("disk full") }
        }
        try {
            GroupRoundCoordinator(broken, queue, responder, scope, logger)
                .start(group, listOf(member(all, "B")), "must not run")
            org.junit.Assert.fail("Admission must fail with storage")
        } catch (_: java.io.IOException) { }
        assertTrue(groups.runningRounds().isEmpty())
        assertTrue(synchronized(recorded) { recorded.isEmpty() })
    }

    @Test fun `failed completion checkpoint never replays an already submitted slot`() = runBlocking {
        val group = seed()
        val all = members()
        val attempted = CompletableDeferred<Unit>()
        val broken = object : GroupChatRepository by groups {
            override suspend fun updateRoundProgress(round: GroupRound) {
                if (round.slots.any { it.status == GroupSlotStatus.PASSED }) {
                    attempted.complete(Unit)
                    throw java.io.IOException("disk full after execution")
                }
                groups.updateRoundProgress(round)
            }
        }
        GroupRoundCoordinator(broken, queue, responder, scope, logger)
            .start(group, listOf(member(all, "B")), "execute once")
        withTimeout(5_000) { attempted.await() }
        assertEquals(GroupSlotStatus.SPEAKING, groups.runningRounds().single().slots.single().status)
        coordinator().recover()
        withTimeout(5_000) { while (groups.runningRounds().isNotEmpty()) delay(10) }
        assertEquals(listOf("B"), synchronized(recorded) { recorded.toList() })
    }
}
