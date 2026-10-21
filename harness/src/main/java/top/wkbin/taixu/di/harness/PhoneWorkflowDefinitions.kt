package top.wkbin.taixu.di.harness

import org.koin.core.module.Module
import top.wkbin.taixu.harness.PhoneAgentServices
import top.wkbin.taixu.harness.workflow.HostActionNodeExecutor
import top.wkbin.taixu.harness.workflow.VirtualScreenWorkflowExecutor
import top.wkbin.taixu.harness.workflow.VirtualScreenWorkflowRuns
import top.wkbin.taixu.runtime.virtualdisplay.PhoneTaskRegistry
import top.wkbin.taixu.runtime.virtualdisplay.VirtualDisplayCoordinator

internal fun Module.phoneWorkflowDefinitions() {
    factory<HostActionNodeExecutor> {
        HostActionNodeExecutor(
            appContext = get(), gui = get(), privilegeManager = get(), linuxRuntime = get(),
            guiPilot = get(), virtualScreen = get(),
        )
    }
    single<PhoneAgentServices> { PhoneAgentServices(events = get(), workflows = get()) }
    single<PhoneTaskRegistry> { get<VirtualDisplayCoordinator>().phoneTasks }
    single<VirtualScreenWorkflowRuns> { VirtualScreenWorkflowRuns(registry = get()) }
    single<VirtualScreenWorkflowExecutor> {
        VirtualScreenWorkflowExecutor(coordinator = get(), toolkit = get(), services = get(), runs = get())
    }
}
