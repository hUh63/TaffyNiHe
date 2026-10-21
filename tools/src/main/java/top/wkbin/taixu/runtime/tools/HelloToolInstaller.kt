package top.wkbin.taixu.runtime.tools

import top.wkbin.taixu.core.common.files.SafeFileTree
import top.wkbin.taixu.core.common.result.AppError
import top.wkbin.taixu.core.common.result.AppResult
import top.wkbin.taixu.core.common.result.ErrorCode
import top.wkbin.taixu.core.model.RuntimeState
import top.wkbin.taixu.core.tools.ToolActionResult
import top.wkbin.taixu.core.tools.ToolRuntimeAdapter
import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.RuntimePathManager
import top.wkbin.taixu.runtime.shell.ShellCommand
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class HelloToolInstaller(
    private val linuxRuntime: LinuxRuntime,
    private val pathManager: RuntimePathManager,
) : ToolRuntimeAdapter {
    override val toolId: String = "hello-tool"

    override fun install(): Flow<InstallEvent> = flow {
        emit(InstallEvent.Started(toolId))
        val distroId = linuxRuntime.activeDistroId.value
        val targetDir = File(pathManager.taixuToolsDir(distroId), toolId)
        val stagingDir = File(pathManager.taixuRootDir(distroId), ".staging-$toolId")
        try {
            if (linuxRuntime.state.value !is RuntimeState.Ready) {
                throw IllegalStateException("Linux Runtime 未就绪，请先初始化 Linux")
            }
            emit(InstallEvent.Progress(toolId, "创建安装事务", 0.25f, InstallEvent.Phase.PREPARING))
            SafeFileTree.delete(stagingDir)
            val binDir = File(stagingDir, "bin")
            binDir.mkdirs()
            val executable = File(binDir, "hello")
            executable.writeText("#!/bin/sh\necho 'Hello TaiXu'\n")
            executable.setExecutable(true, false)
            emit(InstallEvent.Progress(toolId, "验证 hello 命令", 0.85f, InstallEvent.Phase.VERIFYING_INSTALLATION))
            SafeFileTree.delete(targetDir)
            if (!stagingDir.renameTo(targetDir)) {
                throw IllegalStateException("无法提交安装事务")
            }
            val result = linuxRuntime.execute(ShellCommand(ToolLayout.toolBinary(toolId, "hello")))
            if (!result.isSuccess || result.stdout.trim() != "Hello TaiXu") {
                throw IllegalStateException("验证失败：${result.stderr.ifBlank { "输出不匹配" }}")
            }
            emit(InstallEvent.Completed(toolId))
        } catch (cancellation: CancellationException) {
            SafeFileTree.delete(stagingDir)
            SafeFileTree.delete(targetDir)
            throw cancellation
        } catch (throwable: Throwable) {
            SafeFileTree.delete(stagingDir)
            SafeFileTree.delete(targetDir)
            emit(InstallEvent.RolledBack(toolId))
            emit(InstallEvent.Failed(toolId, throwable.message ?: "安装失败"))
        }
    }

    override suspend fun uninstall(deleteData: Boolean): ToolActionResult = runCatching {
        SafeFileTree.delete(File(pathManager.taixuToolsDir(linuxRuntime.activeDistroId.value), "hello-tool"))
    }.fold(
        onSuccess = { ToolActionResult(true, "卸载完成") },
        onFailure = { ToolActionResult(false, "卸载 hello-tool 失败：${it.message.orEmpty()}") },
    )

    override suspend fun launch() = linuxRuntime.execute(
        ShellCommand(ToolLayout.toolBinary(toolId, "hello")),
    )

    override suspend fun verify() = launch()
}
