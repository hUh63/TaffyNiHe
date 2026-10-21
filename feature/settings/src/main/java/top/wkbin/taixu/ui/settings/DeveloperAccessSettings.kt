package top.wkbin.taixu.ui.settings

import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import top.wkbin.taixu.core.datastore.RemoteServerRepository
import top.wkbin.taixu.core.tools.InstalledPluginCatalog
import top.wkbin.taixu.feature.settings.R
import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.bridge.FullAccessGrant
import top.wkbin.taixu.runtime.bridge.HostAccessScope
import top.wkbin.taixu.runtime.bridge.SandboxCredentialStore
import top.wkbin.taixu.ui.components.RuntimeAlertDialog
import top.wkbin.taixu.ui.components.RuntimeIconName
import top.wkbin.taixu.ui.components.RuntimeTextButton

@Composable
internal fun DeveloperAccessSettings(developer: Boolean, change: (Boolean) -> Unit, onOpenDeveloper: () -> Unit, hostControls: Boolean = true) {
    ToggleRow(icon = RuntimeIconName.Bug, title = stringResource(R.string.host_access_developer),
        subtitle = stringResource(R.string.host_access_developer_detail), checked = developer, change = change)
    if (developer) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        SettingsRow(icon = RuntimeIconName.Terminal, title = "开发者控制台", subtitle = "实时查看 PRoot 进程与命令追踪",
            onClick = onOpenDeveloper)
        if (hostControls) HostAccessSettings()
    }
}

private data class HostAccessTarget(val scope: HostAccessScope, val label: String)

@Composable
private fun HostAccessSettings() {
    val grant = koinInject<FullAccessGrant>()
    val runtime = koinInject<LinuxRuntime>()
    val catalog = koinInject<InstalledPluginCatalog>()
    val servers = koinInject<RemoteServerRepository>()
    val remote by servers.activeId.collectAsStateWithLifecycle()
    val distro by runtime.activeDistroId.collectAsStateWithLifecycle()
    val packages by catalog.installed.collectAsStateWithLifecycle()
    val consents by grant.consents.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<HostAccessTarget?>(null) }
    LaunchedEffect(consents) { while (true) { delay(10_000); tick++ } }
    if (remote != null || distro.isBlank()) return
    val targets = listOf(HostAccessTarget(HostAccessScope(distro), stringResource(R.string.host_access_sandbox))) +
        packages.filter { it.distroId == distro }.map {
            HostAccessTarget(HostAccessScope(distro, it.manifest.plugin!!.id, it.manifest.version), it.manifest.name.take(128))
        }
    for (target in targets) {
        val allowed = remember(consents, target, tick) {
            SandboxCredentialStore.SHELL_ACTION in grant.snapshot(target.scope).actions
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        SettingsRow(icon = RuntimeIconName.Admin, title = target.label,
            subtitle = stringResource(R.string.host_access_target, distro, target.scope.pluginId, target.scope.version),
            value = stringResource(if (allowed) R.string.host_access_active else R.string.host_access_request),
            onClick = { pending = target })
    }
    pending?.let { target ->
        val current = targets.any { it == target } && runtime.activeDistroId.value == target.scope.distroId && servers.activeId.value == null
        val allowed = SandboxCredentialStore.SHELL_ACTION in grant.snapshot(target.scope).actions
        RuntimeAlertDialog(onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.host_access_title)) },
            text = { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.host_access_confirmation, target.label, target.scope.distroId,
                    target.scope.pluginId, target.scope.version))
            } },
            confirmButton = {
                RuntimeTextButton(enabled = current, onClick = {
                    if (current) { if (allowed) grant.revoke(target.scope) else grant.allow(target.scope) }
                    pending = null
                }) { Text(stringResource(if (allowed) R.string.host_access_revoke else R.string.host_access_allow)) }
            },
            dismissButton = { RuntimeTextButton(onClick = { pending = null }) { Text(stringResource(R.string.host_access_cancel)) } },
        )
    }
}
