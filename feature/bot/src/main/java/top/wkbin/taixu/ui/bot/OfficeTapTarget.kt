package top.wkbin.taixu.ui.bot

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

/** Invisible room-space hit target for the cat and the coffee machine easter eggs. */
@Composable
internal fun OfficeTapTarget(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    originX: Float,
    originY: Float,
    scale: Float,
    tag: String,
    label: String,
    onClick: () -> Unit,
) {
    val density = LocalDensity.current
    Box(
        Modifier
            .offset {
                IntOffset(
                    (originX + x * scale).roundToInt(),
                    (originY + PixelOfficeScene.y(y) * scale).roundToInt(),
                )
            }
            .size(roomDp(w, scale, density), roomDp(h, scale, density))
            .testTag(tag)
            .semantics { contentDescription = label }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    )
}
