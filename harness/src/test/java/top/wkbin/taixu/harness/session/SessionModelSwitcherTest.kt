package top.wkbin.taixu.harness.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.wkbin.taixu.core.common.logging.AppLogger
import top.wkbin.taixu.core.common.logging.SensitiveDataRedactor
import top.wkbin.taixu.core.database.AiModelEntity
import top.wkbin.taixu.core.database.AppDatabase
import top.wkbin.taixu.core.database.HarnessSessionEntity
import top.wkbin.taixu.core.database.RoomAiModelRepository
import top.wkbin.taixu.core.database.RoomHarnessRuntimeRepository
import top.wkbin.taixu.core.database.RoomHarnessSessionRepository
import top.wkbin.taixu.core.datastore.AgentPreferences
import top.wkbin.taixu.core.datastore.SettingsDataStore
import top.wkbin.taixu.core.security.SecretManager
import top.wkbin.taixu.harness.AssistantText
import top.wkbin.taixu.harness.ModelSwitchEvent
import top.wkbin.taixu.harness.UserMessage
import top.wkbin.taixu.harness.compaction.CompactionManager
import top.wkbin.taixu.harness.projection.LiveMessagePort
import top.wkbin.taixu.harness.HarnessMessage

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionModelSwitcherTest {

    private class RecordingPort : LiveMessagePort {
        val appended = mutableListOf<Pair<String, HarnessMessage>>()

        override suspend fun append(sessionId: String, message: HarnessMessage) {
            appended += sessionId to message
        }

        override suspend fun publishPersisted(sessionId: String, message: HarnessMessage) {
            append(sessionId, message)
        }

        override fun snapshot(sessionId: String): List<HarnessMessage> = emptyList()
    }

    private lateinit var database: AppDatabase
    private lateinit var sessions: RoomHarnessSessionRepository
    private lateinit var models: RoomAiModelRepository
    private lateinit var store: SessionTreeStore
    private lateinit var compaction: CompactionManager
    private lateinit var port: RecordingPort
    private lateinit var switcher: SessionModelSwitcher

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sessions = RoomHarnessSessionRepository(database.harnessSessionDao())
        models = RoomAiModelRepository(database.aiModelDao())
        val json = Json { ignoreUnknownKeys = true }
        val runtimeRepo = RoomHarnessRuntimeRepository(database.harnessRuntimeDao())
        val logger = AppLogger(context, SensitiveDataRedactor { it })
        store = SessionTreeStore(runtimeRepo, json, logger)
        compaction = CompactionManager(runtimeRepo, json, store)
        port = RecordingPort()
        switcher = SessionModelSwitcher(
            sessionDao = sessions,
            modelDao = models,
            settingsDataStore = AgentPreferences(SettingsDataStore(context, SecretManager())),
            compactionManager = compaction,
            messagePort = port,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `switching to a smaller window records a transcript event and compact`() = runBlocking {
        seedProfile("large", "1M Model", "big-model", contextTokens = 1_000_000)
        seedProfile("small", "128K Model", "small-model", contextTokens = 8_000)
        seedSession("s1", "large", "big-model")
        repeat(6) { index ->
            store.append("s1", UserMessage("u-$index", index * 2L, "please review this " + "x".repeat(400)))
            store.append("s1", AssistantText("a-$index", index * 2L + 1, "done " + "y".repeat(400)))
        }

        val result = switcher.switchModel("s1", "small", "small-model")

        assertTrue(result.switched)
        assertTrue(result.compacted)
        assertTrue(result.foldedMessageCount > 0)
        assertEquals(8_000, result.toContextTokens)
        assertEquals("small", sessions.findById("s1")?.modelId)
        assertEquals("small-model", sessions.findById("s1")?.modelVariant)
        val event = port.appended.single().second as ModelSwitchEvent
        assertEquals("128K Model · small-model", event.toLabel)
        assertTrue(event.compacted)
        assertNotNull(compaction.latestSnapshot("s1"))
    }

    @Test
    fun `switching to a larger window does not compact`() = runBlocking {
        seedProfile("small", "128K Model", "small-model", contextTokens = 8_000)
        seedProfile("large", "1M Model", "big-model", contextTokens = 1_000_000)
        seedSession("s2", "small", "small-model")
        store.append("s2", UserMessage("u1", 1L, "hello"))
        store.append("s2", AssistantText("a1", 2L, "hi"))

        val result = switcher.switchModel("s2", "large", "big-model")

        assertTrue(result.switched)
        assertFalse(result.compacted)
        assertEquals(0, result.foldedMessageCount)
        assertEquals(1_000_000, result.toContextTokens)
        val event = port.appended.single().second as ModelSwitchEvent
        assertFalse(event.compacted)
        assertNull(compaction.latestSnapshot("s2"))
    }

    @Test
    fun `selecting the same model is a no-op`() = runBlocking {
        seedProfile("large", "1M Model", "big-model", contextTokens = 1_000_000)
        seedSession("s3", "large", "big-model")

        val result = switcher.switchModel("s3", "large", "big-model")

        assertFalse(result.switched)
        assertTrue(port.appended.isEmpty())
    }

    private suspend fun seedProfile(id: String, name: String, model: String, contextTokens: Int) {
        models.upsert(
            AiModelEntity(
                id = id,
                name = name,
                provider = "openai",
                model = model,
                createdAt = 1L,
                contextTokens = contextTokens,
            ),
        )
    }

    private suspend fun seedSession(id: String, modelId: String, variant: String) {
        sessions.upsert(
            HarnessSessionEntity(
                id = id,
                title = id,
                createdAt = 1L,
                updatedAt = 1L,
                modelId = modelId,
                modelVariant = variant,
            ),
        )
        store.ensureMainLane(id)
    }
}
