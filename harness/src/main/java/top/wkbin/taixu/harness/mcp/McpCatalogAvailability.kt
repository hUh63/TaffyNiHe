package top.wkbin.taixu.harness.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import top.wkbin.taixu.core.model.McpCatalog
import top.wkbin.taixu.core.model.McpServerConfig
import top.wkbin.taixu.core.model.withInstalledComponents
import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.environment.ExecutionEnvironmentContext
import top.wkbin.taixu.runtime.environment.ExecutionEnvironmentFactory
import top.wkbin.taixu.runtime.shell.CommandResult
import top.wkbin.taixu.runtime.shell.ShellCommand

/** 批量本地文件探针；不问 registry，也不启动 MCP 服务器。失败时关闭按需能力。 */
internal suspend fun probeMcpCatalog(
    ids: Set<String> = McpCatalog.installable.map { it.id }.toSet(),
    execute: suspend (ShellCommand) -> CommandResult,
): Set<String> {
    val command = McpCatalog.installable.filter { it.id in ids }.joinToString("\n") {
        "if ${it.probeCommand}; then printf '%s\\n' '${it.installComponentId}'; fi"
    } + "\nexit 0"
    return try {
        val result = execute(ShellCommand(commandLine = command, timeoutMs = 8_000L))
        if (result.isSuccess) result.stdout.lineSequence().map { it.trim() }
            .filter { id -> McpCatalog.installable.any { it.installComponentId == id } }.toSet() else emptySet()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptySet()
    }
}

class McpCatalogAvailability(
    private val runtime: LinuxRuntime,
    private val environments: ExecutionEnvironmentFactory? = null,
    private val remoteServers: top.wkbin.taixu.core.datastore.RemoteServerRepository? = null,
) {
    suspend fun resolve(servers: List<McpServerConfig>): List<McpServerConfig> {
        if (servers.none { it.requiresInstall }) return servers
        val bound = currentCoroutineContext()[ExecutionEnvironmentContext]?.environment
        if (bound == null && environments == null && remoteServers?.activeId?.value != null)
            return servers.map { it.withInstalledComponents(emptySet()) }
        val owned = try {
            if (bound == null && environments != null) {
                val scope = remoteServers?.let { StandaloneMcpScope(it, { runtime.activeDistroId.value }).captureKey() }
                    ?: "local:${runtime.activeDistroId.value}"
                environments.open("mcp:catalog-probe@environment:$scope", "", null)
            } else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return servers.map { it.withInstalledComponents(emptySet()) }
        }
        var distribution = ""
        val installed = try {
            val world = bound ?: owned
            distribution = if (world?.id?.backend == "ssh") "" else world?.id?.distribution ?: runtime.activeDistroId.value
            val ids = servers.filter { it.requiresInstall }.map { it.id }.toSet()
            if (world != null) probeMcpCatalog(ids) { world.execute(it) }
            else probeMcpCatalog(ids) { runtime.execute(it) }
        } finally {
            if (owned != null) withContext(NonCancellable) { owned.close() }
        }
        return servers.map { server ->
            val resolved = server.withInstalledComponents(installed)
            if (!server.requiresInstall || distribution.isBlank()) resolved else resolved.copy(
                catalogDistribution = distribution,
                catalogRevision = top.wkbin.taixu.core.common.McpCatalogRevisions.current(distribution, server.id),
            )
        }
    }
}
