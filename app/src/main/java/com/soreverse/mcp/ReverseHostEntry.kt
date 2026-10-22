package com.soreverse.mcp

import androidx.compose.runtime.Composable
import top.wkbin.taixu.ui.components.HostWorkspaceRegistry

/**
 * 把塔菲逆核的整个工作区（首页 / 分析 / 任务 / 编辑器 / 设置）注册为太墟主导航的「逆向」中枢。
 *
 * 注册在 `MainActivity.onCreate` 里、早于任何 Compose 内容读取注册表，因此太墟壳一渲染
 * 「逆向」中枢就能拿到宿主内容；重复调用是幂等的（宿主可能因配置变更多次走 onCreate）。
 *
 * 注意方向：`:taixu` 不依赖 `:app`，只依赖 `feature:components` 里的这个注册表接口，
 * 所以宿主界面能嵌进太墟导航而不会产生模块环。
 */
internal object ReverseWorkspaceEntry {
    private const val ID = "reverse"

    fun ensureRegistered() {
        HostWorkspaceRegistry.register(
            object : HostWorkspaceRegistry.Provider {
                override val id: String = ID

                @Composable
                override fun Content() {
                    IntegrityGate { SoReverseApp(hostThemed = true) }
                }
            },
        )
    }
}
