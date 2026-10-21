package top.wkbin.taixu.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class McpToolPolicyPreferencesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun enabledAndRiskPersistPerTool() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { File(temporary.root, "mcp-policy.preferences_pb") },
            )
            val prefs = McpToolPolicyPreferences(store)
            assertTrue(prefs.policyFor("s1", "query").enabled)

            prefs.setEnabled("s1", "query", false)
            prefs.setRiskOverride("s1", "query", "critical")
            val query = McpToolPolicyPreferences(store).policyFor("s1", "query")
            assertFalse(query.enabled)
            assertEquals("critical", query.riskOverride)
            assertTrue(prefs.policyFor("s1", "other").enabled)

            prefs.setRiskOverride("s1", "query", "bogus")
            assertNull(prefs.policyFor("s1", "query").riskOverride)
        } finally {
            scope.cancel()
        }
    }
}
