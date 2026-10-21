package top.wkbin.taixu.di.harness

import org.koin.core.scope.Scope
import top.wkbin.taixu.harness.plugins.HarnessPluginRegistry
import top.wkbin.taixu.harness.plugins.PluginNodeExecutor
import top.wkbin.taixu.harness.workflow.*

internal fun builtinWorkflowExecutors(scope: Scope): Set<NodeExecutor> = with(scope) {
    setOf(get<PassthroughNodeExecutor>(), get<ApprovalNodeExecutor>(), get<LinuxNodeExecutor>(), get<AgentNodeExecutor>(),
        get<ConditionNodeExecutor>(), get<SetVariableNodeExecutor>(), get<HostActionNodeExecutor>(),
        PluginNodeExecutor(get(), get(), get())) + get<HarnessPluginRegistry>().workflowExecutors()
}
