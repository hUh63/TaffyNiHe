package top.wkbin.taixu.plugin.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import top.wkbin.taixu.plugin.runtime.PluginController
import top.wkbin.taixu.plugin.runtime.PluginLease
import top.wkbin.taixu.plugin.runtime.PluginPhase

val LocalPluginController = staticCompositionLocalOf<PluginController?> { null }

/** The running state is a hint; synchronous admission checks again against the current generation. */
@Composable
internal fun rememberPluginLease(controller: PluginController, id: String, phase: PluginPhase?, generation: Long): PluginLease? {
    var lease by remember(controller, id, generation) { mutableStateOf<PluginLease?>(null) }
    DisposableEffect(controller, id, phase, generation) {
        val acquired = if (phase == PluginPhase.RUNNING) controller.acquire(id) else null
        lease = acquired
        onDispose { acquired?.close() }
    }
    return lease.takeIf { phase == PluginPhase.RUNNING && it?.activation?.generation == generation }
}
