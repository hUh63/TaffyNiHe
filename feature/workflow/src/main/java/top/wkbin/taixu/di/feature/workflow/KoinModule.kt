package top.wkbin.taixu.di.feature.workflow

import org.koin.dsl.module
import top.wkbin.taixu.ui.workflow.WorkflowViewModel
import org.koin.core.module.dsl.viewModel

/** Dependency registrations owned by the feature:workflow module. */
val featureWorkflowModule = module {
    viewModel<WorkflowViewModel> {
        WorkflowViewModel(
            repository = get(),
            runManager = get(),
            scheduleRepository = get(),
            hud = get(),
            appContext = get(),
            aiModelRepository = get(),
            pathManager = get(),
            json = get(),
        )
    }
}
