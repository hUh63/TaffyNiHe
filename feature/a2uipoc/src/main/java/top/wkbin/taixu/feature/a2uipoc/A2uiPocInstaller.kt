package top.wkbin.taixu.feature.a2uipoc

import android.content.Context
import top.wkbin.taixu.harness.A2uiSurfaceBus

/**
 * A2UI PoC 的装配入口：官方 A2UI parser 只允许在 feature:a2uipoc 使用
 * （architecture-policy），而 render_surface 的深度校验发生在 harness 的
 * A2uiSurfaceBus——通过钩子把真校验注册进总线，并启动用户交互事件转发。
 * 由 ChatViewModel init 调用（幂等）。
 */
object A2uiPocInstaller {

    @Volatile
    private var installed = false

    fun install(context: Context) {
        TaiXuUrlOpener.bind(context)
        if (installed) return
        synchronized(this) {
            if (installed) return
            A2uiSurfaceBus.installDeepValidator { messagesJson -> TaiXuA2uiRenderer.validateMessages(messagesJson) }
            A2uiSurfaceBus.onSurfacesReleased = { TaiXuA2uiRenderer.releaseSurfaces(it) }
            TaiXuA2uiRenderer.startEventForwarding()
            installed = true
        }
    }
}
