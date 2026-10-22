package top.wkbin.taixu.di.runtime

import org.koin.dsl.module
import org.koin.dsl.onClose
import top.wkbin.taixu.runtime.backend.BackendSelector
import top.wkbin.taixu.runtime.backend.ModeBackendSelector
import top.wkbin.taixu.runtime.droidspaces.DroidspacesCommandBuilder
import top.wkbin.taixu.runtime.proot.ProotCommandBuilder
import top.wkbin.taixu.core.database.BuildScriptRepository
import top.wkbin.taixu.runtime.BackgroundTaskRegistry
import top.wkbin.taixu.runtime.DistroConfigurator
import top.wkbin.taixu.runtime.ElfInspector
import top.wkbin.taixu.runtime.EnvironmentResolver
import top.wkbin.taixu.runtime.FtpServiceManager
import top.wkbin.taixu.runtime.LinuxEnvironmentManager
import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.LinuxRuntimeImpl
import top.wkbin.taixu.runtime.LocalLlmManager
import top.wkbin.taixu.runtime.RuntimeHealthChecker
import top.wkbin.taixu.runtime.RuntimePathManager
import top.wkbin.taixu.runtime.SshServiceManager
import top.wkbin.taixu.runtime.StorageManager
import top.wkbin.taixu.runtime.WorkspaceFileService
import top.wkbin.taixu.runtime.WorkspaceManager
import top.wkbin.taixu.runtime.apps.AndroidAppManager
import top.wkbin.taixu.runtime.bridge.FullAccessGrant
import top.wkbin.taixu.runtime.bridge.HostBridge
import top.wkbin.taixu.runtime.bridge.HostCredentialAuthority
import top.wkbin.taixu.runtime.bridge.SandboxCredentialStore
import top.wkbin.taixu.runtime.bridge.adb.EmbeddedAdbManager
import top.wkbin.taixu.runtime.build.WorkshopSigningManager
import top.wkbin.taixu.runtime.build.WorkspaceBuildRunner
import top.wkbin.taixu.runtime.doctor.EnvironmentDoctor
import top.wkbin.taixu.runtime.doctor.ToolchainInspector
import top.wkbin.taixu.runtime.doctor.ToolchainRepairer
import top.wkbin.taixu.runtime.doctor.EnvironmentRepairer
import top.wkbin.taixu.runtime.gui.GuiAccessibilityEnabler
import top.wkbin.taixu.runtime.gui.HostGuiController
import top.wkbin.taixu.runtime.gui.HostGuiToolkit
import top.wkbin.taixu.runtime.gui.WorkflowGuiHudBridge
import top.wkbin.taixu.runtime.privilege.PrivilegeManager
import top.wkbin.taixu.runtime.privilege.ShizukuHostServiceClient
import top.wkbin.taixu.runtime.privilege.ShizukuSystemApis
import top.wkbin.taixu.runtime.virtualdisplay.VirtualDisplayCoordinator
import top.wkbin.taixu.runtime.virtualdisplay.VirtualScreenToolkit
import top.wkbin.taixu.runtime.proot.ProotInstaller
import top.wkbin.taixu.runtime.pty.NativePtyManager
import top.wkbin.taixu.runtime.pty.ScriptPtyManager
import top.wkbin.taixu.runtime.rootfs.LxcImagesClient
import top.wkbin.taixu.runtime.rootfs.OciRegistryClient
import top.wkbin.taixu.runtime.rootfs.RootfsInstaller
import top.wkbin.taixu.runtime.rootfs.RootfsValidator
import top.wkbin.taixu.runtime.rootfs.TarStreamExtractor
import top.wkbin.taixu.runtime.scripts.RuntimeAssetSynchronizer
import top.wkbin.taixu.runtime.service.LocalServiceLauncherImpl
import top.wkbin.taixu.runtime.shell.ProcessRegistryImpl
import top.wkbin.taixu.runtime.shell.ProcessShellExecutor
import top.wkbin.taixu.runtime.terminal.TerminalSessionClientRouter
import top.wkbin.taixu.runtime.terminal.TerminalSessionManager
import top.wkbin.taixu.runtime.webchat.WebChatBridgeServer

/** Dependency registrations owned by the runtime module. */
val runtimeModule = module {
    single { top.wkbin.taixu.runtime.environment.LocalDistributionSelection(get<top.wkbin.taixu.core.datastore.RuntimePreferences>(), get<top.wkbin.taixu.runtime.LinuxRuntime>()) }
    single { top.wkbin.taixu.runtime.environment.LocalBackendAccess(get()) }
    single { top.wkbin.taixu.runtime.SessionWorkspaceCatalog(servers = get(), remote = get(), local = get()) }
    single {
        val manager = get<LinuxEnvironmentManager>()
        top.wkbin.taixu.runtime.remote.SshConnector { manager.snapshotForSsh() }
    }
    single<top.wkbin.taixu.runtime.remote.SshConnectionTester> { get<top.wkbin.taixu.runtime.remote.SshConnector>() }
    single { top.wkbin.taixu.runtime.remote.RemoteSystemService(get(), get()) }
    /**
     * 后端选型收敛点：PRoot 与 Droidspaces 两个实现常驻，由调用点在**自己的挂起路径**里
     * 解析当前模式（`effectiveExecutionMode.first()`）。因此单条命令与会话在其生命周期内
     * 固定使用同一个后端，也不存在异步快照窗口；切换 ROOT 模式后对下一条命令即时生效。
     */
    single<ProotCommandBuilder> { ProotCommandBuilder(environmentResolver = get(), logger = get()) }
    single<DroidspacesCommandBuilder> {
        DroidspacesCommandBuilder(environmentResolver = get(), logger = get())
    }
    single<BackendSelector> {
        ModeBackendSelector(
            proot = get<ProotCommandBuilder>(),
            droidspaces = get<DroidspacesCommandBuilder>(),
            modes = get<top.wkbin.taixu.core.datastore.RuntimePreferences>().effectiveExecutionMode,
            containerBackend = get<top.wkbin.taixu.core.datastore.ContainerBackendPreferences>().enabled,
        )
    }

    single<BackgroundTaskRegistry> { BackgroundTaskRegistry() }

    single<DistroConfigurator> {
        DistroConfigurator(
            pathManager = get(),
            assetSynchronizer = get(),
            logger = get(),
        )
    }

    /** 宿主凭据权威：插件宿主调用与沙箱凭据共用同一签发/校验路径，禁用旧共享密钥。 */
    single<HostCredentialAuthority> { HostCredentialAuthority() }

    /** 开发者偏好仅开启确认入口；临时授权保存在内存并在每次鉴权时复核。 */
    single<FullAccessGrant> {
        FullAccessGrant(developerMode = get<top.wkbin.taixu.core.datastore.AppearancePreferences>().developerMode)
    }.onClose { it?.close() }

    single<SandboxCredentialStore> {
        val grant = get<FullAccessGrant>()
        SandboxCredentialStore(authority = get(), grantSnapshot = grant::snapshot, onRevokeDistro = grant::revokeDistro)
    }

    single<ElfInspector> { ElfInspector() }

    single<EnvironmentResolver> { EnvironmentResolver() }

    single<FtpServiceManager> {
        FtpServiceManager(
            context = get(),
            linuxRuntime = get(),
            preferences = get(),
            sshPreferences = get(),
        )
    }

    single<LinuxEnvironmentManager> { LinuxEnvironmentManager(linuxRuntime = get(), runtimePreferences = get()) }

    single<LinuxRuntimeImpl> {
        LinuxRuntimeImpl(
            pathManager = get(),
            prootInstaller = get(),
            rootfsInstaller = get(),
            backendSelector = get<BackendSelector>(),
            ptyManager = get(),
            shellExecutor = get(),
            healthChecker = get(),
            processRegistry = get(),
            settingsDataStore = get(),
            storageMountBindingRepository = get(),
            hostBridge = get(),
            sandboxCredentials = get(),
            distroConfigurator = get(),
            logger = get(),
        )
    }

    single<LocalLlmManager> {
        LocalLlmManager(
            context = get(),
            pathManager = get(),
            linuxRuntime = get(),
            fileDownloader = get(),
            serviceLauncher = get(),
        )
    }

    single<RuntimeHealthChecker> {
        RuntimeHealthChecker(
            pathManager = get(),
            backendSelector = get<BackendSelector>(),
            shellExecutor = get(),
        )
    }

    single<RuntimePathManager> { RuntimePathManager(context = get(), rootfsValidator = get()) }

    single<SshServiceManager> {
        SshServiceManager(
            context = get(),
            linuxRuntime = get(),
            preferences = get(),
            serviceLauncher = get(),
        )
    }

    single<StorageManager> {
        StorageManager(
            context = get(),
            pathManager = get(),
            runtime = lazy { get<LinuxRuntime>() },
        )
    }

    single<WorkspaceFileService> { WorkspaceFileService(pathManager = get(), workspaceRepository = get()) }

    single<WorkspaceManager> {
        WorkspaceManager(
            context = get(),
            pathManager = get(),
            workspaceDao = get(),
            fileService = get(),
            linuxRuntime = lazy { get<LinuxRuntime>() },
            projectTemplateEngine = get(),
            buildScriptRepository = lazy { get<BuildScriptRepository>() },
        )
    }

    single<AndroidAppManager> {
        AndroidAppManager(
            context = get(),
            privilegeManager = get(),
            repository = get(),
        )
    }

    single<HostBridge> {
        HostBridge(
            context = get(),
            logger = get(),
            privilegeManager = get(),
            embeddedAdbManager = get(),
            pathManager = get(),
            credentialAuthority = get(),
        )
    }

    single<EmbeddedAdbManager> {
        EmbeddedAdbManager(
            context = get(),
            preferences = get(),
            pathManager = get(),
        )
    }

    single<WorkshopSigningManager> {
        WorkshopSigningManager(
            context = get(),
            linuxRuntime = get(),
            pathManager = get(),
            preferences = get(),
            assetSynchronizer = get(),
        )
    }

    single<WorkspaceBuildRunner> {
        WorkspaceBuildRunner(
            context = get(),
            linuxRuntime = get(),
            embeddedAdbManager = get(),
            assetSynchronizer = get(),
            runtimePreferences = get(),
            workshopPreferences = get(),
            buildScriptRepository = get(),
            signingManager = get(),
            logger = get(),
        )
    }

    single<EnvironmentDoctor> { EnvironmentDoctor(context = get(), linuxRuntime = get()) }

    single<EnvironmentRepairer> { EnvironmentRepairer(linuxRuntime = get(), environmentDoctor = get()) }

    // 沙箱工具链检测与补齐：单例活过聊天页，离开界面不会取消 apt/dpkg
    single<ToolchainInspector> { ToolchainInspector(linuxRuntime = get()) }
    single<ToolchainRepairer> {
        ToolchainRepairer(linuxRuntime = get(), installs = get(), inspector = get())
    }

    single<GuiAccessibilityEnabler> { GuiAccessibilityEnabler(context = get(), privilegeManager = get()) }

    single<HostGuiController> {
        HostGuiController(
            context = get(),
            privilegeManager = get(),
            toolkit = get(),
            hud = get(),
        )
    }

    single<HostGuiToolkit> {
        HostGuiToolkit(
            context = get(),
            privilegeManager = get(),
            accessibilityEnabler = get(),
        )
    }

    single<WorkflowGuiHudBridge> { WorkflowGuiHudBridge() }

    single<PrivilegeManager> {
        PrivilegeManager(
            context = get(),
            settingsDataStore = get(),
            logger = get(),
            shizukuHostServiceClient = get(),
        )
    }

    // 虚拟屏门面：首次 get 时把 ShellRunner 装配进 ShowerEnvironment（showerclient 注入点）
    single<VirtualDisplayCoordinator> {
        VirtualDisplayCoordinator(
            context = get(),
            privilegeManager = get(),
            logger = get(),
        )
    }

    // 虚拟屏 GUI 原语执行器：复用 GuiPrimitive，定向到指定会话的 Shower 虚拟屏
    single<VirtualScreenToolkit> {
        VirtualScreenToolkit(
            context = get(),
            coordinator = get(),
        )
    }

    single<ShizukuHostServiceClient> { ShizukuHostServiceClient(context = get()) }

    single<ShizukuSystemApis> { ShizukuSystemApis(context = get()) }

    single<ProotInstaller> {
        ProotInstaller(
            pathManager = get(),
            elfInspector = get(),
            logger = get(),
        )
    }

    single<ScriptPtyManager> { ScriptPtyManager() }

    single<NativePtyManager> { NativePtyManager(scriptFallback = get()) }

    single<LxcImagesClient> { LxcImagesClient(http = get(), logger = get()) }

    single<OciRegistryClient> { OciRegistryClient(http = get(), logger = get()) }

    single<RootfsInstaller> {
        RootfsInstaller(
            pathManager = get(),
            tarStreamExtractor = get(),
            rootfsValidator = get(),
            logger = get(),
            ociRegistryClient = get(),
            lxcImagesClient = get(),
        )
    }

    single<RootfsValidator> { RootfsValidator(elfInspector = get()) }

    single<RuntimeAssetSynchronizer> { RuntimeAssetSynchronizer(context = get(), pathManager = get()) }

    single<LocalServiceLauncherImpl> { LocalServiceLauncherImpl(linuxRuntime = get()) }

    single<ProcessRegistryImpl> {
        ProcessRegistryImpl(
            pathManager = get(),
            backendSelector = get<BackendSelector>(),
            sandboxCredentials = get(),
        )
    }

    single<ProcessShellExecutor> { ProcessShellExecutor(pathManager = get()) }

    single<TerminalSessionClientRouter> { TerminalSessionClientRouter() }
    single<top.wkbin.taixu.runtime.terminal.TerminalProcessFactory> { top.wkbin.taixu.runtime.terminal.TermuxTerminalProcessFactory() }
    single<top.wkbin.taixu.runtime.terminal.RemoteTerminalProcessFactory> {
        top.wkbin.taixu.runtime.terminal.TermuxRemoteTerminalProcessFactory()
    }

    single<TerminalSessionManager> {
        TerminalSessionManager(
            linuxRuntime = get(),
            terminalSessionDao = get(),
            sessionClientRouter = get(),
            // 未限定 = 当前选中的执行环境（本地/远程），远程终端据此走 SSH 渲染分支。
            environmentFactory = get(),
            processFactory = get(),
            remoteProcessFactory = get(),
        )
    }

    single<WebChatBridgeServer> {
        WebChatBridgeServer(
            context = get(),
            sessions = get(),
            models = get(),
            quickPhrases = get(),
            workspaces = get(),
            workspaceManager = get(),
            workspaceFiles = get(),
            linuxRuntime = get(),
            agentGateway = get(),
            logger = get(),
        )
    }

    single<TarStreamExtractor> { TarStreamExtractor(logger = get()) }
}
