package top.wkbin.taixu.di.feature.developer

import org.koin.dsl.module
import top.wkbin.taixu.ui.developer.DeveloperViewModel
import org.koin.core.module.dsl.viewModel

/** Dependency registrations owned by the feature:developer module. */
val featureDeveloperModule = module {
    viewModel<DeveloperViewModel> {
        DeveloperViewModel(
            linuxRuntime = get(),
            runtimeManager = get(),
            runtimePreferences = get(),
            agentPreferences = get(),
            registryPreferences = get(),
            onboardingPreferences = get(),
            toolRegistry = get(),
            toolManager = get(),
            logger = get(),
            embeddedAdbManager = get(),
        )
    }
}
