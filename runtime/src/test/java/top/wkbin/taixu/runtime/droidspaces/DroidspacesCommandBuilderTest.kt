package top.wkbin.taixu.runtime.droidspaces

import top.wkbin.taixu.core.model.ExecutionMode
import top.wkbin.taixu.core.model.StorageMountBinding
import top.wkbin.taixu.runtime.EnvironmentResolver
import top.wkbin.taixu.runtime.backend.ModeBackendSelector
import top.wkbin.taixu.runtime.proot.ProotCommandBuilder
import top.wkbin.taixu.runtime.shell.SessionConfig
import top.wkbin.taixu.runtime.shell.ShellCommand
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Droidspaces 后端参数构造的纯函数测试。
 *
 * 只验证 argv 形态（可离线复算），不验证真机行为——容器是否真能跑由 P0/P5 门禁与真机验证覆盖。
 */
class DroidspacesCommandBuilderTest {

    private val rootfsDir = File("/data/data/app/files/linux-runtime/distros/ubuntu/rootfs")
    private val workspaceDir = File("/data/data/app/files/linux-runtime/workspace")
    private val optDir = File("/data/data/app/files/linux-runtime/distros/ubuntu/opt/taixu")
    private val tmpDir = File("/data/data/app/files/linux-runtime/tmp")
    private val attachmentsDir = File("/data/data/app/files/linux-runtime/attachments")
    private val homeDir = File("/data/data/app/files/linux-runtime/distros/ubuntu/home")

    private fun builder() = DroidspacesCommandBuilder(EnvironmentResolver())

    private fun build(commandLine: String = "true"): List<String> = builder().build(
        prootBinary = File("/data/data/app/files/linux-runtime/bin/prisma"),
        rootfsDir = rootfsDir,
        workspaceDir = workspaceDir,
        homeDir = homeDir,
        optDir = optDir,
        tmpDir = tmpDir,
        attachmentsDir = attachmentsDir,
        command = ShellCommand(commandLine = commandLine),
        mounts = emptyList(),
        emulatorBinary = null,
        hostCredential = emptyMap(),
    )

    @Test
    fun buildWrapsEverythingInSuDashC() {
        // argv 形状固定为 `su -c <script>`：脚本是第 3 个元素（下标 2），不是第 2 个。
        val argv = build()
        assertEquals(3, argv.size)
        assertEquals("su", argv[0])
        assertEquals("-c", argv[1])
        assertTrue(argv[2].isNotBlank())
    }

    @Test
    fun hostScriptRunsAsRootInTheDistroContainer() {
        val script = build()[2]
        // 外层 `su -c` 已是 UID 0，droidspaces 侧刻意不传 `-u`：它只在 main 的
        // discovery pass 里被解析，而该 pass 遇到 `run` 就 break（main.c:938-948），
        // 写在 `run` 之后既不会生效、还会污染 run_argv（= argv + optind + 1）。
        // as_user 为空时 run_in_rootfs 直接 execvp（container.c:1650-1657），即以 root 执行。
        assertTrue(script.contains("run -- "))
        assertFalse("`-u` 不得出现在 `run` 之后", script.contains("run -u"))
        // 容器名由 distro id 派生，rootfsDir 的父目录名即 distro id
        assertTrue(script.contains("taixu-ubuntu"))
    }

    @Test
    fun containerIsStartedWithRootfsWhenNotYetRunning() {
        val script = build()[2]
        assertTrue("必须先确保容器在跑，否则 run 会失败", script.contains("start --name="))
        assertTrue(script.contains(rootfsDir.absolutePath))
    }

    @Test
    fun guestDirectoryViewMatchesProotBackend() {
        val script = build()[2]
        // 这五项若缺失，插件与工具链在容器内将完全不可见或与 PRoot 视图隔开
        assertTrue(script.contains("${workspaceDir.absolutePath}:/workspace"))
        assertTrue(script.contains("${homeDir.absolutePath}:/root"))
        assertTrue(script.contains("${optDir.absolutePath}:/opt/taixu"))
        assertTrue(script.contains("${tmpDir.absolutePath}:/tmp"))
        assertTrue(script.contains("${attachmentsDir.absolutePath}:/attachments"))
    }

    @Test
    fun commandIsExecutedInRequestedWorkingDirectory() {
        val script = build(commandLine = "ls -la")[2]
        assertTrue(script.contains("cd -- "))
        assertTrue(script.contains("ls -la"))
        // 不得给 guest 命令加 `exec`：调用方传入的是任意 shell 片段，`exec` 只接受一条
        // 简单命令，会让 `a; b` 的 b 不执行、`cd` / `if` 这类内建与关键字直接失败。
        assertFalse("不得给 guest 命令加 exec 前缀", script.contains("exec ls -la"))
        assertFalse(script.contains("&& exec "))
    }

    @Test(expected = IllegalArgumentException::class)
    fun qemuCompatibilityIsRejectedInsteadOfSilentlyFailing() {
        builder().build(
            prootBinary = File("/p"),
            rootfsDir = rootfsDir,
            workspaceDir = workspaceDir,
            homeDir = File("/h"),
            optDir = optDir,
            tmpDir = tmpDir,
            attachmentsDir = attachmentsDir,
            command = ShellCommand(commandLine = "true"),
            mounts = emptyList(),
            emulatorBinary = File("/qemu-x86_64"),
            hostCredential = emptyMap(),
        )
    }

    @Test
    fun disabledStorageMountIsNotForwarded() {
        val argv = builder().build(
            prootBinary = File("/p"),
            rootfsDir = rootfsDir,
            workspaceDir = workspaceDir,
            homeDir = File("/h"),
            optDir = optDir,
            tmpDir = tmpDir,
            attachmentsDir = attachmentsDir,
            command = ShellCommand(commandLine = "true"),
            mounts = listOf(
                StorageMountBinding(
                    id = "test-disabled",
                    name = "已停用",
                    hostPath = "/storage/emulated/0/Download",
                    guestPath = "/mnt/download",
                    enabled = false,
                ),
            ),
            emulatorBinary = null,
            hostCredential = emptyMap(),
        )
        assertFalse(argv[2].contains("/mnt/download"))
    }

    @Test
    fun illegalStorageMountIsSkippedNotCrashing() {
        val argv = builder().build(
            prootBinary = File("/p"),
            rootfsDir = rootfsDir,
            workspaceDir = workspaceDir,
            homeDir = File("/h"),
            optDir = optDir,
            tmpDir = tmpDir,
            attachmentsDir = attachmentsDir,
            command = ShellCommand(commandLine = "true"),
            mounts = listOf(
                // 容器路径不在 /mnt 或 /sdcard 内 → 必须被过滤，不能注入 argv
                StorageMountBinding(
                    id = "test-illegal",
                    name = "非法路径",
                    hostPath = "/storage/emulated/0/Download",
                    guestPath = "/etc",
                ),
            ),
            emulatorBinary = null,
            hostCredential = emptyMap(),
        )
        assertFalse(argv[2].contains("/etc"))
    }

    @Test
    fun interactiveSessionCarriesBannerAndGeometry() {
        val argv = builder().buildInteractive(
            prootBinary = File("/p"),
            rootfsDir = rootfsDir,
            workspaceDir = workspaceDir,
            homeDir = File("/h"),
            optDir = optDir,
            tmpDir = tmpDir,
            attachmentsDir = attachmentsDir,
            config = SessionConfig(columns = 120, rows = 40, showBanner = true, usePty = true),
            ptyMarker = null,
            nativePty = false,
            mounts = emptyList(),
            emulatorBinary = null,
            hostCredential = emptyMap(),
        )
        val script = argv[2]
        assertTrue(script.contains(DroidspacesCommandBuilder.GUEST_MOTD))
        assertTrue(script.contains("stty cols 120 rows 40"))
    }

    @Test
    fun protocolSessionStaysOnPlainPipe() {
        val argv = builder().buildInteractive(
            prootBinary = File("/p"),
            rootfsDir = rootfsDir,
            workspaceDir = workspaceDir,
            homeDir = File("/h"),
            optDir = optDir,
            tmpDir = tmpDir,
            attachmentsDir = attachmentsDir,
            config = SessionConfig(commandLine = "/bin/sh", usePty = false, showBanner = false),
            ptyMarker = null,
            nativePty = false,
            mounts = emptyList(),
            emulatorBinary = null,
            hostCredential = emptyMap(),
        )
        val script = argv[2]
        // MCP STDIO 会话不得混入 banner 与 stty，否则污染协议输出流
        assertFalse(script.contains(DroidspacesCommandBuilder.GUEST_MOTD))
        assertFalse(script.contains("stty cols"))
    }

    @Test
    fun selectorRoutesToProotUntilRootModeIsSelected() {
        runBlocking {
            val modes = MutableStateFlow(ExecutionMode.PROOT)
            val containerBackend = MutableStateFlow(false)
            val selector = ModeBackendSelector(
                proot = ProotCommandBuilder(EnvironmentResolver()),
                droidspaces = builder(),
                modes = modes,
                containerBackend = containerBackend,
            )
            // 模式取自 Flow 的当前值，不依赖任何调度器上的异步收集器，因此无竞态。
            assertEquals("/data/app/proot", sampleArgv(selector).first())

            // ROOT 模式但实验开关仍关闭：必须继续走 PRoot（对现有 ROOT 用户零行为变化）
            modes.value = ExecutionMode.ROOT
            assertEquals("/data/app/proot", sampleArgv(selector).first())

            // 只有显式打开开关，才切换到容器后端
            containerBackend.value = true
            assertEquals("su", sampleArgv(selector).first())
        }
    }

    /** 用同一组入参驱动后端，断言「同一份输入在不同模式下走不同后端」。 */
    private fun sampleArgv(selector: ModeBackendSelector): List<String> = runBlocking {
        selector.active().build(
            prootBinary = File("/data/app/proot"),
            rootfsDir = rootfsDir,
            workspaceDir = workspaceDir,
            homeDir = File("/h"),
            optDir = optDir,
            tmpDir = tmpDir,
            attachmentsDir = attachmentsDir,
            command = ShellCommand(commandLine = "true"),
            mounts = emptyList(),
            emulatorBinary = null,
            hostCredential = emptyMap(),
        )
    }
}
