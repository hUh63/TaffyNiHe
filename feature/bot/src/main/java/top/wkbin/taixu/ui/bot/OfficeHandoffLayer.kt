package top.wkbin.taixu.ui.bot

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import top.wkbin.taixu.core.model.bot.Bot
import top.wkbin.taixu.core.model.bot.BotAssignment
import top.wkbin.taixu.core.model.bot.BotAssignmentStatus
import top.wkbin.taixu.core.model.bot.BotAvatarState
import top.wkbin.taixu.core.model.bot.PixelAvatarRecipe
import kotlin.math.max
import kotlin.math.roundToInt

internal data class OfficeWalkHandoff(
    val taskId: String,
    val fromBotId: String,
    val toBotId: String,
    val completedAt: Long,
)

internal fun officeWalkHandoffs(
    assignments: List<BotAssignment>,
    members: List<Bot>,
    now: Long,
): List<OfficeWalkHandoff> {
    val memberIds = members.map { it.id }.toSet()
    return assignments.asSequence()
        .sortedByDescending { it.finishedAt ?: 0L }
        .mapNotNull { task ->
            val finished = task.finishedAt ?: return@mapNotNull null
            if (task.status != BotAssignmentStatus.COMPLETED) return@mapNotNull null
            if (now - finished !in 0..HANDOFF_WINDOW_MS) return@mapNotNull null
            if (task.botId !in memberIds) return@mapNotNull null
            val dependent = assignments.asSequence()
                .filter { dependency ->
                    task.id in dependency.dependsOn &&
                        (dependency.status == BotAssignmentStatus.RUNNING ||
                            dependency.status == BotAssignmentStatus.QUEUED)
                }
                .filter { it.botId in memberIds }
                .minByOrNull { it.startedAt }
            val target = dependent?.botId ?: task.reportToBotId ?: return@mapNotNull null
            if (task.botId == target || target !in memberIds) return@mapNotNull null
            OfficeWalkHandoff(dependent?.id ?: task.id, task.botId, target, finished)
        }
        .take(2)
        .toList()
}

/** Only same-floor pairs walk; cross-floor handoffs keep the flying folder on the link. */
internal fun walkableHandoffs(
    handoffs: List<OfficeWalkHandoff>,
    actors: List<OfficeActor>,
): List<OfficeWalkHandoff> {
    val byId = actors.associateBy { it.botId }
    return handoffs.filter { handoff ->
        val from = byId[handoff.fromBotId] ?: return@filter false
        val to = byId[handoff.toBotId] ?: return@filter false
        officeFloorOf(from.y) == officeFloorOf(to.y)
    }
}

internal data class OfficeHandoffState(
    val handoffs: List<OfficeWalkHandoff>,
    val courierIds: Set<String>,
    val taskIds: Set<String>,
)

internal fun officeHandoffState(
    handoffs: List<OfficeWalkHandoff>,
    actors: List<OfficeActor>,
): OfficeHandoffState {
    val walkable = walkableHandoffs(handoffs, actors)
    return OfficeHandoffState(
        handoffs = walkable,
        courierIds = walkable.mapTo(HashSet()) { it.fromBotId },
        taskIds = walkable.mapTo(HashSet()) { it.taskId },
    )
}

private fun officeFloorOf(sceneY: Int): Int = when {
    sceneY < 66 -> 0
    sceneY < 156 -> 1
    sceneY < 274 -> 2
    else -> 3
}

private const val HANDOFF_WINDOW_MS = 4_500L
private const val WALK_OUT_MS = 1_100L
private const val TRANSFER_MS = 1_500L
private const val WALK_BACK_MS = 2_600L

/** Draws the same-floor courier walking a folder to the report recipient and back. */
@Composable
internal fun OfficeHandoffLayer(
    handoffs: List<OfficeWalkHandoff>,
    actors: List<OfficeActor>,
    recipes: Map<String, PixelAvatarRecipe>,
    originX: Int,
    originY: Int,
    scale: Float,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "office-handoff")
    val tick = transition.animateFloat(
        initialValue = 0f,
        targetValue = 64f,
        animationSpec = infiniteRepeatable(tween(8000, easing = LinearEasing), RepeatMode.Restart),
        label = "office-handoff-tick",
    )
    val slot by remember { derivedStateOf { tick.value.toInt().coerceIn(0, 63) } }
    val now = remember(slot) { System.currentTimeMillis() }
    Canvas(modifier.testTag("office-handoff-layer")) {
        val byId = actors.associateBy { it.botId }
        handoffs.forEach { handoff ->
            val from = byId[handoff.fromBotId] ?: return@forEach
            val to = byId[handoff.toBotId] ?: return@forEach
            val age = now - handoff.completedAt
            if (age !in 0..6_000L) return@forEach
            drawCourier(handoff, from, to, age, recipes, originX, originY, scale)
        }
    }
}

private fun DrawScope.drawCourier(
    handoff: OfficeWalkHandoff,
    from: OfficeActor,
    to: OfficeActor,
    age: Long,
    recipes: Map<String, PixelAvatarRecipe>,
    originX: Int,
    originY: Int,
    scale: Float,
) {
    val sourceX = originX + from.x * scale
    val sourceY = originY + from.y * scale
    val targetX = originX + to.x * scale
    val targetY = originY + to.y * scale
    val feet = when {
        age <= WALK_OUT_MS -> lerpPoint(sourceX, sourceY, targetX, targetY, smooth(age / WALK_OUT_MS.toFloat()))
        age <= TRANSFER_MS -> Offset(targetX, targetY)
        age <= WALK_BACK_MS -> lerpPoint(
            targetX, targetY, sourceX, sourceY,
            smooth((age - TRANSFER_MS) / (WALK_BACK_MS - TRANSFER_MS).toFloat()),
        )
        else -> Offset(sourceX, sourceY)
    }
    drawOval(
        Color(0x332A1E14),
        Offset(feet.x - 5f * scale, feet.y - 1.4f * scale),
        Size(10f * scale, 2.6f * scale),
    )
    val recipe = recipes[handoff.fromBotId] ?: PixelAvatarRecipe.fromName(handoff.fromBotId)
    val frame = ((age / 160L) % 3L).toInt()
    val bitmap = PixelOfficeFrames.bitmap(recipe, OfficePose.STAND, BotAvatarState.ACTIVE, frame, false, false)
    val width = (PixelOfficeSprite.WORLD_WIDTH * scale).roundToInt().coerceAtLeast(1)
    val height = (PixelOfficeSprite.WORLD_HEIGHT * scale).roundToInt().coerceAtLeast(1)
    drawImage(
        bitmap,
        dstOffset = IntOffset((feet.x - width / 2f).roundToInt(), (feet.y - height).roundToInt()),
        dstSize = IntSize(width, height),
        filterQuality = FilterQuality.None,
    )
    when {
        age <= WALK_OUT_MS -> drawFolder(feet.x + 5f * scale, feet.y - 10f * scale, scale)
        age <= TRANSFER_MS -> {
            val p = smooth((age - WALK_OUT_MS) / (TRANSFER_MS - WALK_OUT_MS).toFloat())
            val folder = lerpPoint(
                targetX + 5f * scale, targetY - 10f * scale,
                targetX, targetY - 14f * scale,
                p,
            )
            drawFolder(folder.x, folder.y, scale)
        }
        age <= TRANSFER_MS + 700L -> {
            val alpha = (1f - (age - TRANSFER_MS) / 700f).coerceIn(0f, 1f)
            drawSparkle(targetX, targetY - 15f * scale, scale, Color(0xFF8FD18A).copy(alpha = alpha))
        }
    }
}

private fun lerpPoint(x0: Float, y0: Float, x1: Float, y1: Float, t: Float): Offset =
    Offset(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)

private fun smooth(t: Float): Float {
    val clamped = t.coerceIn(0f, 1f)
    return clamped * clamped * (3f - 2f * clamped)
}

private fun DrawScope.drawFolder(x: Float, y: Float, scale: Float) {
    val w = 3.2f * scale
    val h = 2.2f * scale
    drawRect(Color(0xFF2A2119), Offset(x - w / 2f, y - h / 2f), Size(w, h))
    drawRect(Color(0xFFF6E7C8), Offset(x - w / 2f + 1f, y - h / 2f + 1f), Size(w - 2f, h - 2f))
    drawRect(Color(0xFF7EC8E3), Offset(x - w / 2f + 1f, y - h / 2f + 1f), Size(w - 2f, h * 0.36f))
    drawRect(Color(0xFF2A2119), Offset(x - w / 2f + 1f, y - h / 2f + h * 0.36f), Size(w - 2f, 1f))
}

private fun DrawScope.drawSparkle(x: Float, y: Float, scale: Float, color: Color) {
    val dot = max(1f, 0.7f * scale)
    val arm = 2.2f * scale
    drawRect(color, Offset(x - dot / 2f, y - arm), Size(dot, arm * 2f))
    drawRect(color, Offset(x - arm, y - dot / 2f), Size(arm * 2f, dot))
}
