package top.wkbin.taixu.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wkbin.taixu.core.database.HarnessSessionEntity
import top.wkbin.taixu.runtime.ProjectType
import top.wkbin.taixu.runtime.WorkspaceProject

class SessionPartitionTest {

    private fun createSession(
        id: String,
        title: String,
        workspace: String = "",
        updatedAt: Long = 0L,
    ): HarnessSessionEntity = HarnessSessionEntity(
        id = id,
        title = title,
        createdAt = updatedAt - 1000L,
        updatedAt = updatedAt,
        modelId = null,
        workspace = workspace,
    )

    private fun createProject(name: String, linuxPath: String, projectType: ProjectType = ProjectType.GENERAL) =
        WorkspaceProject(
            name = name,
            path = linuxPath,
            linuxPath = linuxPath,
            sizeBytes = 1024L,
            projectType = projectType,
        )

    @Test
    fun testPartitionWithProjectsAndRecent() {
        val p1 = createProject("taixu", "/workspace/taixu", ProjectType.ANDROID)
        val p2 = createProject("backend", "/workspace/backend", ProjectType.GENERAL)

        val s1 = createSession("s1", "taixu task 1", "/workspace/taixu", updatedAt = 100L)
        val s2 = createSession("s2", "taixu task 2", "/workspace/taixu", updatedAt = 300L)
        val s3 = createSession("s3", "recent task old", "", updatedAt = 200L)
        val s4 = createSession("s4", "recent task new", "", updatedAt = 500L)
        val s5 = createSession("s5", "backend task", "/workspace/backend", updatedAt = 400L)

        val result = partitionSessions(listOf(s1, s2, s3, s4, s5), listOf(p1, p2))

        // Recent sessions: unassociated, sorted by updatedAt DESC
        assertEquals(2, result.recentSessions.size)
        assertEquals("s4", result.recentSessions[0].id)
        assertEquals("s3", result.recentSessions[1].id)

        // Project groups
        assertEquals(2, result.projectGroups.size)
        val taixuGroup = result.projectGroups.first { it.projectName == "taixu" }
        assertEquals(2, taixuGroup.sessions.size)
        // Sessions in project sorted by updatedAt DESC
        assertEquals("s2", taixuGroup.sessions[0].id)
        assertEquals("s1", taixuGroup.sessions[1].id)
        assertEquals(ProjectType.ANDROID, taixuGroup.projectType)

        val backendGroup = result.projectGroups.first { it.projectName == "backend" }
        assertEquals(1, backendGroup.sessions.size)
        assertEquals("s5", backendGroup.sessions[0].id)
    }

    @Test
    fun testEmptyProjectsStillShownWithEmptySessions() {
        val p1 = createProject("empty-proj", "/workspace/empty-proj")
        val s1 = createSession("s1", "recent 1", "", updatedAt = 100L)

        val result = partitionSessions(listOf(s1), listOf(p1))

        assertEquals(1, result.recentSessions.size)
        assertEquals(1, result.projectGroups.size)
        assertEquals("empty-proj", result.projectGroups[0].projectName)
        assertTrue(result.projectGroups[0].sessions.isEmpty())
    }

    @Test
    fun testOrphanWorkspaceSessionFallback() {
        // Session workspace path that no longer exists in workspaces list
        val s1 = createSession("s1", "orphan task", "/workspace/deleted-proj", updatedAt = 100L)

        val result = partitionSessions(listOf(s1), emptyList())

        assertEquals(0, result.recentSessions.size)
        assertEquals(1, result.projectGroups.size)
        assertEquals("deleted-proj", result.projectGroups[0].projectName)
        assertEquals("s1", result.projectGroups[0].sessions[0].id)
    }

    @Test
    fun testAllRecentSessions() {
        val s1 = createSession("s1", "task 1", "", updatedAt = 10L)
        val s2 = createSession("s2", "task 2", "", updatedAt = 50L)
        val s3 = createSession("s3", "task 3", "", updatedAt = 30L)

        val result = partitionSessions(listOf(s1, s2, s3), emptyList())

        assertTrue(result.projectGroups.isEmpty())
        assertEquals(3, result.recentSessions.size)
        assertEquals("s2", result.recentSessions[0].id)
        assertEquals("s3", result.recentSessions[1].id)
        assertEquals("s1", result.recentSessions[2].id)
    }
}
