package top.wkbin.taixu.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import top.wkbin.taixu.core.database.InstallLogEntity
import top.wkbin.taixu.core.tools.PackagePluginInventory
import top.wkbin.taixu.core.tools.ToolManager
import top.wkbin.taixu.plugin.packages.PackagedPluginEntry

data class PackagedPluginsState(val entries: List<PackagedPluginEntry> = emptyList(), val failed: Boolean = false)

class PackagedPluginsViewModel(
    inventory: PackagePluginInventory,
    plugins: top.wkbin.taixu.harness.plugins.HarnessPluginRegistry,
    private val tools: ToolManager,
) : ViewModel() {
    val harnessPlugins = plugins.descriptors
    val state = inventory.observe().map { PackagedPluginsState(it) }.catch { emit(PackagedPluginsState(failed = true)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PackagedPluginsState())

    fun installLogs(toolId: String): Flow<List<InstallLogEntity>> = tools.observeInstallLogs(toolId)
}
