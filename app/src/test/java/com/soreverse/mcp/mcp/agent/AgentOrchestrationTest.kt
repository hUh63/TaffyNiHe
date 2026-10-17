package com.soreverse.mcp.mcp.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编排层的纯逻辑单测：计划结构、证据解析、预算。
 *
 * 这里不碰网络与 Android 运行时——子代理真正跑模型的部分只能在真机上验，但「计划对不对、
 * 证据能不能被正确解析、预算会不会失效」这三件事必须能在 JVM 上钉死。
 */
class AgentOrchestrationTest {

    @Test
    fun planPutsReconFirstAndReportLast() {
        val nodes = AgentOrchestrator.plan("看看这个 so 有没有加固")
        assertTrue("plan should not be empty", nodes.isNotEmpty())

        val recon = nodes.first()
        assertEquals("recon", recon.roleId)
        assertTrue("recon has no dependencies", recon.dependsOn.isEmpty())

        val report = nodes.last()
        assertEquals("report", report.roleId)
        assertTrue("report must be aggregate", report.aggregate)
        assertEquals("report depends on every other node", nodes.size - 1, report.dependsOn.size)
    }

    @Test
    fun flowDependsOnCodeNotOnRecon() {
        val nodes = AgentOrchestrator.plan("goal")
        val code = nodes.first { it.roleId == "code" }
        val flow = nodes.first { it.roleId == "flow" }
        assertTrue("flow must depend on code", flow.dependsOn.contains(code.id))
    }

    @Test
    fun planRespectsRoleFilter() {
        val nodes = AgentOrchestrator.plan("goal", listOf("recon", "packer"))
        val roles = nodes.map { it.roleId }
        assertEquals(listOf("recon", "packer", "report"), roles)
        assertEquals("report still aggregates the filtered set", 2, nodes.last().dependsOn.size)
    }

    @Test
    fun planIgnoresUnknownRoles() {
        val nodes = AgentOrchestrator.plan("goal", listOf("nonexistent", "recon"))
        assertEquals(listOf("recon", "report"), nodes.map { it.roleId })
    }

    @Test
    fun evidenceParserReadsWellFormedBlock() {
        val text = """
            Some analysis body.

            EVIDENCE: [{"role":"packer","claim":"libjiagu present","where":".rodata@0x1234","confidence":"high"},
                       {"claim":"no ptrace import","where":".dynsym","confidence":"medium"}]
        """.trimIndent()
        val ev = AgentEvidenceParser.parse(text)
        assertEquals(2, ev.size)
        assertEquals("libjiagu present", ev[0].claim)
        assertEquals(".rodata@0x1234", ev[0].where)
        assertEquals("high", ev[0].confidence)
        assertEquals("medium", ev[1].confidence)
    }

    @Test
    fun evidenceParserToleratesGarbage() {
        assertEquals(0, AgentEvidenceParser.parse("no evidence here").size)
        assertEquals(0, AgentEvidenceParser.parse("EVIDENCE: [not json").size)
        assertEquals(0, AgentEvidenceParser.parse("EVIDENCE: []").size)
        assertEquals(0, AgentEvidenceParser.parse("EVIDENCE: [{\"where\":\"x\"}]").size)
    }

    @Test
    fun evidenceBodyStripsMarker() {
        val body = AgentEvidenceParser.body("finding A\nEVIDENCE: [{\"claim\":\"a\"}]")
        assertEquals("finding A", body)
    }

    @Test
    fun budgetStopsAtQuota() {
        val budget = AgentBudget(maxSubAgents = 2, maxToolCalls = 3, wallClockMs = 60_000)
        assertTrue(budget.tryConsumeAgent())
        assertTrue(budget.tryConsumeAgent())
        assertTrue("third agent must be refused", !budget.tryConsumeAgent())

        assertTrue(budget.tryConsumeTool())
        assertTrue(budget.tryConsumeTool())
        assertTrue(budget.tryConsumeTool())
        assertTrue("fourth tool call must be refused", !budget.tryConsumeTool())

        assertEquals(3, budget.agentsUsed())
        assertEquals(4, budget.toolsUsed())
        assertTrue(budget.healthy())
    }

    @Test
    fun budgetIsUnhealthyAfterDeadlineOrCancel() {
        val budget = AgentBudget(maxSubAgents = 1, maxToolCalls = 1, wallClockMs = 60_000)
        budget.cancel()
        assertTrue("cancelled budget must be unhealthy", !budget.healthy())

        val expiring = AgentBudget(wallClockMs = -1L)
        assertTrue("expired budget must be unhealthy", !expiring.healthy())
    }

    @Test
    fun rolesHaveUniqueIdsAndSaneToolLists() {
        val ids = AgentRoles.ALL.map { it.id }
        assertEquals("role ids must be unique", ids.size, ids.toSet().size)
        assertNotNull(AgentRoles.byId("packer"))
        assertEquals("REPORT must have no tools", 0, AgentRoles.byId("report")!!.toolNames.size)
        AgentRoles.ALL.filter { it.id != "report" }.forEach { role ->
            assertTrue("${role.id} needs at least one tool", role.toolNames.isNotEmpty())
        }
    }

    @Test
    fun systemPromptCarriesScopeAndEvidenceRule() {
        val prompt = AgentRoles.systemPrompt(AgentRoles.byId("packer")!!, zh = true, workspaceId = "ws-1")
        assertTrue(prompt.contains("EVIDENCE"))
        assertTrue(prompt.contains("taffy_so_packer_fingerprint"))
        assertTrue(prompt.contains("ws-1"))
    }
}
