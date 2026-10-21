package top.wkbin.taixu.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import top.wkbin.taixu.core.datastore.McpClientPreferences
import top.wkbin.taixu.core.model.McpIdlePolicy

class McpClientSettingsViewModel(private val preferences: McpClientPreferences) : ViewModel() {
    val idleMinutes = preferences.idleTimeoutMinutes.stateIn(viewModelScope, SharingStarted.Eagerly, McpIdlePolicy.DEFAULT_MINUTES)
    private val failed = MutableStateFlow(false)
    val saveFailed = failed.asStateFlow()
    fun setIdleMinutes(minutes: Int) {
        viewModelScope.launch {
            try { preferences.setIdleTimeoutMinutes(minutes); failed.value = false }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed.value = true }
        }
    }
}
