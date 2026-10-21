package top.wkbin.taixu.di.feature.onboarding

import org.koin.dsl.module
import top.wkbin.taixu.ui.onboarding.OnboardingViewModel
import org.koin.core.module.dsl.viewModel

/** Dependency registrations owned by the feature:onboarding module. */
val featureOnboardingModule = module {
    viewModel<OnboardingViewModel> {
        OnboardingViewModel(
            context = get(),
            settings = get(),
            linuxRuntime = get(),
            providerRepository = get(),
            providerCatalogRepository = get(),
            modelDiscovery = get(),
            toolManager = get(),
            profileWriter = get(),
            profileBackupCodec = get(),
        )
    }
}
