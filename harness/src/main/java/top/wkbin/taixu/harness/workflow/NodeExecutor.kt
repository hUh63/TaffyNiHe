package top.wkbin.taixu.harness.workflow

import top.wkbin.taixu.core.model.workflow.NodeExecutionOutput
import top.wkbin.taixu.core.model.workflow.NodeRunStatus
import top.wkbin.taixu.core.model.workflow.WorkflowNode
import top.wkbin.taixu.core.model.workflow.WorkflowNodeType
import top.wkbin.taixu.core.model.workflow.WorkflowRuntimeContext

interface NodeExecutor {
    val supportedTypes: Set<WorkflowNodeType>

    suspend fun execute(
        node: WorkflowNode,
        context: WorkflowRuntimeContext,
        onProgress: suspend (NodeRunStatus, String) -> Unit,
    ): NodeExecutionOutput
}

