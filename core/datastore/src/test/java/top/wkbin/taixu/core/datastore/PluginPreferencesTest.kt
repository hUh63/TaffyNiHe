package top.wkbin.taixu.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginPreferencesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun selectionSurvivesStoreRecreationAndOnlyChangesRequestedIdentity() = runBlocking {
        val file = File(temporary.root, "plugins.preferences_pb")
        val firstJob = SupervisorJob()
        val firstStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(firstJob + Dispatchers.IO), produceFile = { file })
        val first = PluginPreferences(firstStore)
        assertTrue(first.disabledPlugins().isEmpty())
        first.setEnabled("taixu.terminal", false)
        first.setEnabled("taixu.browser", false)
        first.setEnabled("taixu.browser", true)
        firstJob.cancelAndJoin()
        val secondJob = SupervisorJob()
        try {
            val second = PluginPreferences(PreferenceDataStoreFactory.create(scope = CoroutineScope(secondJob + Dispatchers.IO), produceFile = { file }))
            assertEquals(setOf("taixu.terminal"), second.disabledPlugins())
            second.setEnabled("taixu.terminal", true)
            assertTrue(second.disabledPlugins().isEmpty())
        } finally { secondJob.cancelAndJoin() }
    }
}
