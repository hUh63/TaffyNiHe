package com.soreverse.mcp.mcp.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentBudgetTest {

    private fun budgetAt(elapsedMs: Long, wall: Long = 10 * 60_000L): AgentBudget =
        AgentBudget(wallClockMs = wall, startedAtMs = System.currentTimeMillis() - elapsedMs)

    @Test
    fun keepsFullLimitAtStart() {
        assertEquals(100_000, budgetAt(0).toolResultLimit(100_000))
        assertEquals(100_000, budgetAt(60_000).toolResultLimit(100_000)) // 10%
    }

    @Test
    fun tightensAsBudgetBurns() {
        val base = 100_000
        assertEquals(75_000, budgetAt(4 * 60_000L).toolResultLimit(base)) // 40%
        assertEquals(50_000, budgetAt(7 * 60_000L).toolResultLimit(base)) // 70%
        assertEquals(25_000, budgetAt(9 * 60_000L).toolResultLimit(base)) // 90%
    }

    @Test
    fun neverTruncatesBelowFloor() {
        val tiny = budgetAt(9 * 60_000L).toolResultLimit(4_000)
        assertTrue("floor 2000 must apply, got $tiny", tiny == 2_000)
    }

    @Test
    fun zeroOrNegativeBaseIsPassedThrough() {
        assertEquals(0, budgetAt(9 * 60_000L).toolResultLimit(0))
        assertEquals(-5, budgetAt(9 * 60_000L).toolResultLimit(-5))
    }

    @Test
    fun expiredAfterWallClock() {
        assertTrue(budgetAt(11 * 60_000L, wall = 10 * 60_000L).expired())
    }
}
