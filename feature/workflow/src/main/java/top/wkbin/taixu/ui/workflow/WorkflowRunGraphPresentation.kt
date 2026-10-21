package top.wkbin.taixu.ui.workflow

import top.wkbin.taixu.core.model.workflow.WorkflowDefinition
import top.wkbin.taixu.core.model.workflow.WorkflowLayout
import top.wkbin.taixu.core.model.workflow.WorkflowValidator

/** Display-only projection: the approved definition and recovery journal retain original coordinates. */
internal fun workflowRunGraphPresentation(definition: WorkflowDefinition, expanded: Boolean): WorkflowDefinition =
    if (expanded && WorkflowValidator.validate(definition).isEmpty()) WorkflowLayout.arrange(definition) else definition
