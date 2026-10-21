package top.wkbin.taixu.harness.groupchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wkbin.taixu.core.model.bot.Bot
import top.wkbin.taixu.core.model.bot.GroupChat
import top.wkbin.taixu.core.model.bot.GroupRoundSlot

class GroupRoundPromptsTest {
    private val bot = Bot(
        id = "bot1",
        name = "Bot1",
        role = "member",
        systemPrompt = "",
        avatarSeed = "seed",
        sessionId = "session",
        createdAt = 1,
        updatedAt = 1,
    )
    private val group = GroupChat(id = "group", name = "Team", adminBotId = "bot1", createdAt = 1)

    @Test fun `short speaking order lists every member`() {
        val slots = (1..3).map { GroupRoundSlot("bot$it", "Bot$it") }
        assertEquals("Bot1 → Bot2 → Bot3", formatSpeakingOrder(slots))
    }

    @Test fun `long speaking order lists a bounded preview and the total`() {
        val slots = (1..15).map { GroupRoundSlot("bot$it", "Bot$it") }
        val order = formatSpeakingOrder(slots)
        assertTrue(order.startsWith("Bot1"))
        assertTrue(order.contains("Bot12"))
        assertFalse(order.contains("Bot13"))
        assertTrue(order.endsWith("…（共 15 位）"))
    }

    @Test fun `trigger uses the bounded order`() {
        val slots = (1..15).map { GroupRoundSlot("bot$it", "Bot$it") }
        val trigger = buildRoundTrigger(group, bot, slots.first(), slots, 0, "task")
        assertTrue(trigger.contains("共 15 位"))
        assertFalse(trigger.contains("Bot13"))
    }
}
