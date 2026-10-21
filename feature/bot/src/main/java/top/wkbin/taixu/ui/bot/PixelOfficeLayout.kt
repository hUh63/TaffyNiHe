package top.wkbin.taixu.ui.bot

import top.wkbin.taixu.core.model.bot.Bot
import top.wkbin.taixu.core.model.bot.BotAvatarState

/** Where a bot stands in the pixel office, in room pixels. Feet sit at [x], [y]. */
internal enum class OfficePose { STAND, SIT, TYPE, THINK }

/** Small, deterministic idle business so a waiting bot never looks frozen. */
internal enum class OfficeIdleAction { NONE, SIP, READ, STRETCH, PHONE, DOZE }

internal data class OfficeSeat(val id: String, val x: Int, val y: Int, val pose: OfficePose)

internal data class OfficeActor(
    val botId: String,
    val name: String,
    val x: Int,
    val y: Int,
    val pose: OfficePose,
    val state: BotAvatarState,
    val waitSeconds: Int? = null,
    val idleAction: OfficeIdleAction = OfficeIdleAction.NONE,
)


internal data class OfficeWait(val sessionId: String, val createdAt: Long, val expiresAt: Long)

/** Shifts each bot onto its own beat so the room does not breathe in unison. */
internal fun officeFrameOffset(botId: String): Int = (botId.hashCode() and Int.MAX_VALUE) % 3

/**
 * Assigns each member a stable spot in the office. Active bots take the four
 * monitors first, then the meeting-table hot desks; idle bots take sofas and
 * the rug; overflow uses the named aisle spots before the walkway fallback.
 */
internal object PixelOfficeLayout {
    private val desks = listOf(
        OfficeSeat("desk", 28, 224, OfficePose.TYPE),
        OfficeSeat("desk-b", 72, 224, OfficePose.TYPE),
        OfficeSeat("desk-c", 28, 272, OfficePose.TYPE),
        OfficeSeat("desk-d", 72, 272, OfficePose.TYPE),
        OfficeSeat("desk-e", 126, 224, OfficePose.TYPE),
        OfficeSeat("desk-f", 166, 224, OfficePose.TYPE),
        OfficeSeat("desk-g", 126, 272, OfficePose.TYPE),
        OfficeSeat("desk-h", 166, 272, OfficePose.TYPE),
    )
    private val meeting = listOf(
        OfficeSeat("meet-a", 114, 138, OfficePose.SIT),
        OfficeSeat("meet-b", 136, 138, OfficePose.SIT),
        OfficeSeat("meet-c", 158, 138, OfficePose.SIT),
        OfficeSeat("meet-d", 96, 138, OfficePose.SIT),
    )
    private val board = listOf(OfficeSeat("board", 92, 164, OfficePose.THINK))
    private val lounge = listOf(
        OfficeSeat("sofa-l", 40, 376, OfficePose.SIT),
        OfficeSeat("sofa-r", 116, 378, OfficePose.SIT),
        OfficeSeat("rug", 84, 400, OfficePose.SIT),
    )
    private val coffee = listOf(
        OfficeSeat("coffee-a", 156, 380, OfficePose.SIT),
        OfficeSeat("coffee-b", 178, 380, OfficePose.SIT),
    )
    private val floor = listOf(
        OfficeSeat("window", 174, 144, OfficePose.STAND),
        OfficeSeat("poster", 164, 380, OfficePose.STAND),
        OfficeSeat("hall", 70, 410, OfficePose.STAND),
    )

    fun place(
        members: List<Bot>,
        states: Map<String, BotAvatarState>,
        now: Long = 0L,
        standup: Set<String> = emptySet(),
    ): List<OfficeActor> {
        val used = mutableSetOf<String>()
        return members.sortedWith(
            compareBy({ if (it.id in standup) -1 else statePriority(states[it.id] ?: BotAvatarState.IDLE) }, { it.id }),
        )
            .map { bot ->
                val state = states[bot.id] ?: BotAvatarState.IDLE
                val inStandup = bot.id in standup
                val wanted = if (inStandup) OfficePose.SIT else poseFor(state)
                val preferred = if (inStandup) meeting else when (state) {
                    BotAvatarState.THINKING -> board
                    BotAvatarState.EXECUTING, BotAvatarState.ACTIVE -> desks
                    BotAvatarState.BLOCKED, BotAvatarState.FAILED -> coffee
                    BotAvatarState.IDLE, BotAvatarState.SUCCESS -> lounge
                    else -> floor
                }
                val fallback = if (inStandup) meeting + floor else when (state) {
                    BotAvatarState.THINKING,
                    BotAvatarState.EXECUTING,
                    BotAvatarState.ACTIVE,
                    -> meeting + floor
                    BotAvatarState.IDLE,
                    BotAvatarState.SUCCESS,
                    -> meeting + coffee + floor
                    else -> floor
                }
                val seat = claim(used, preferred, fallback)
                val roam = if (inStandup) null else idleRoam(seat, bot.id, state, now)
                val pose = if (roam != null) OfficePose.STAND else resolvePose(wanted, seat.id)
                OfficeActor(
                    botId = bot.id,
                    name = bot.name,
                    x = roam?.first ?: seat.x,
                    y = roam?.second ?: seat.y,
                    pose = pose,
                    state = state,
                    idleAction = if (inStandup || roam != null) OfficeIdleAction.NONE
                    else idleAction(bot, state, seat.id, now),
                )
            }
    }

    /** Marks bots whose session is waiting on an approval or a user question. */
    fun applyWaiting(
        actors: List<OfficeActor>,
        sessionOf: Map<String, String>,
        waits: List<OfficeWait>,
        now: Long,
    ): List<OfficeActor> {
        if (waits.isEmpty()) return actors.map { it.copy(waitSeconds = null) }
        return actors.map { actor ->
            val session = sessionOf[actor.botId]
            val matched = waits.filter { it.sessionId == session }
            if (session == null || matched.isEmpty()) actor.copy(waitSeconds = null)
            else actor.copy(waitSeconds = matched.minOf { waitSeconds(it, now) })
        }
    }

    fun waitSeconds(wait: OfficeWait, now: Long): Int {
        val remaining = ((wait.expiresAt - now) / 1000L).toInt()
        if (wait.expiresAt == Long.MAX_VALUE || remaining > 24 * 3600) {
            return (60 - ((now - wait.createdAt) / 1000L).toInt()).coerceIn(0, 60)
        }
        return remaining.coerceAtLeast(0)
    }

    /** At most two speech bubbles: the selected bot, then whoever is busiest. */
    fun speakerIds(actors: List<OfficeActor>, selectedId: String?): List<String> {
        if (actors.isEmpty()) return emptyList()
        val busy = actors.filter { it.state != BotAvatarState.IDLE }
            .sortedBy { stateRank(it.state) }
            .map { it.botId }
        val ordered = buildList {
            if (selectedId != null && actors.any { it.botId == selectedId }) add(selectedId)
            busy.forEach { if (it !in this) add(it) }
            if (isEmpty()) add(actors.first().botId)
        }
        return ordered.take(2)
    }

    private fun poseFor(state: BotAvatarState): OfficePose = when (state) {
        BotAvatarState.THINKING -> OfficePose.THINK
        BotAvatarState.EXECUTING, BotAvatarState.ACTIVE -> OfficePose.TYPE
        BotAvatarState.IDLE, BotAvatarState.SUCCESS -> OfficePose.SIT
        BotAvatarState.BLOCKED, BotAvatarState.FAILED -> OfficePose.STAND
        else -> OfficePose.STAND
    }

    private fun resolvePose(wanted: OfficePose, seatId: String): OfficePose = when (wanted) {
        OfficePose.TYPE -> if (seatId.startsWith("desk") || seatId.startsWith("meet")) OfficePose.TYPE else OfficePose.STAND
        OfficePose.THINK -> when {
            seatId == "board" -> OfficePose.THINK
            seatId.startsWith("meet") || seatId.startsWith("sofa") || seatId == "rug" -> OfficePose.SIT
            else -> OfficePose.STAND
        }
        OfficePose.SIT -> when {
            seatId.startsWith("meet") || seatId.startsWith("sofa") ||
                seatId.startsWith("coffee") || seatId == "rug" -> OfficePose.SIT
            else -> OfficePose.STAND
        }
        OfficePose.STAND -> OfficePose.STAND
    }

    private fun idleAction(
        bot: Bot,
        state: BotAvatarState,
        seatId: String,
        now: Long,
    ): OfficeIdleAction {
        if (state != BotAvatarState.IDLE) return OfficeIdleAction.NONE
        if (seatId.startsWith("coffee")) return OfficeIdleAction.SIP
        if (now <= 0L) return OfficeIdleAction.NONE
        val slot = (((now / 9_000L) + (bot.id.hashCode() and Int.MAX_VALUE)) % 6L).toInt()
        return when (slot) {
            0 -> OfficeIdleAction.SIP
            1 -> OfficeIdleAction.READ
            2 -> OfficeIdleAction.STRETCH
            3 -> OfficeIdleAction.PHONE
            4 -> OfficeIdleAction.DOZE
            else -> OfficeIdleAction.NONE
        }
    }

    /** A short, deterministic walk to a nearby aisle during an idle cycle. */
    private fun idleRoam(
        seat: OfficeSeat,
        botId: String,
        state: BotAvatarState,
        now: Long,
    ): Pair<Int, Int>? {
        if (state != BotAvatarState.IDLE || now <= 0L) return null
        val target = when {
            seat.id == "sofa-l" -> 24 to 386
            seat.id == "sofa-r" -> 100 to 388
            seat.id == "rug" -> 68 to 392
            seat.id.startsWith("coffee") -> if (seat.x < 170) 138 to 390 else 166 to 390
            seat.id.startsWith("meet") -> when (seat.x) {
                114 -> 96 to 150
                136 -> 118 to 152
                else -> 176 to 150
            }
            else -> return null
        }
        val phase = ((now / 1000L + (botId.hashCode() and Int.MAX_VALUE)) % 48L).toInt()
        return if (phase in 26..33) target else null
    }

    private fun claim(used: MutableSet<String>, preferred: List<OfficeSeat>, fallback: List<OfficeSeat>): OfficeSeat {
        val seat = (preferred + fallback).firstOrNull { it.id !in used }
        if (seat != null) {
            used += seat.id
            return seat
        }
        val index = used.size
        val overflow = index - (used.count { !it.startsWith("extra-") })
        val rows = listOf(148, 288, 408)
        val extra = OfficeSeat(
            "extra-$index", 20 + (overflow % 6) * 29,
            rows[(overflow / 6) % rows.size] + (overflow / 18) * 26, OfficePose.STAND,
        )
        used += extra.id
        return extra
    }

    private fun statePriority(state: BotAvatarState): Int = when (state) {
        BotAvatarState.EXECUTING, BotAvatarState.ACTIVE -> 0
        BotAvatarState.THINKING -> 1
        BotAvatarState.BLOCKED, BotAvatarState.FAILED -> 2
        BotAvatarState.SUCCESS -> 3
        BotAvatarState.IDLE -> 4
    }

    private fun stateRank(state: BotAvatarState): Int = when (state) {
        BotAvatarState.ACTIVE -> 0
        BotAvatarState.EXECUTING -> 1
        BotAvatarState.THINKING -> 2
        BotAvatarState.BLOCKED -> 3
        BotAvatarState.FAILED -> 4
        BotAvatarState.SUCCESS -> 5
        BotAvatarState.IDLE -> 6
    }
}
