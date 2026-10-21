package top.wkbin.taixu.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf

/**
 * 宿主工作区接入点。
 *
 * 塔菲逆核等宿主工程把太墟（wkbin/taixu）整树移植后，希望自己的界面**作为太墟主导航的一个
 * 中枢**呈现，而不是另开一个 Activity 各走一套底部导航。
 *
 * 但模块依赖方向不允许太墟反过来依赖宿主（`:taixu` ← `:app`），因此这里提供一个极薄的注册表：
 * 宿主在启动时把自己的根 Composable 注册进来，太墟导航的「逆向」中枢渲染它。
 *
 * 约定：
 * - 同一 [Provider.id] 重复注册只保留首个（宿主多次调用 ensureRegistered 是幂等的）；
 * - 没有宿主注册时，中枢显示空态而不崩溃（纯太墟构建仍可用）。
 */
object HostWorkspaceRegistry {

    interface Provider {
        val id: String

        @Composable
        fun Content()
    }

    private val _providers = mutableStateListOf<Provider>()

    val providers: List<Provider>
        get() = _providers

    fun register(provider: Provider) {
        if (_providers.none { it.id == provider.id }) _providers.add(provider)
    }

    fun unregister(id: String) {
        _providers.removeAll { it.id == id }
    }
}

/** 太墟「逆向」中枢的内容：首个已注册宿主工作区。 */
@Composable
fun HostWorkspaceContent() {
    HostWorkspaceRegistry.providers.firstOrNull()?.Content()
}
