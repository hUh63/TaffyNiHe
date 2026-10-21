package top.wkbin.taixu.ui.workflow

import java.io.File
import top.wkbin.taixu.core.model.workflow.WorkflowRuntimeState

data class DiscoveredApk(
    val file: File,
    val name: String,
    val relativePath: String,
    val sandboxPath: String,
    val sizeBytes: Long,
    val lastModified: Long,
)

data class WorkflowHistoryEntry(
    val state: WorkflowRuntimeState,
    val triggerSource: String,
    val scheduleId: String?,
)
