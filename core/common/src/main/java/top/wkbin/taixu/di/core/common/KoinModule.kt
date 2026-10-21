package top.wkbin.taixu.di.core.common

import org.koin.dsl.module
import top.wkbin.taixu.core.common.logging.AppLogger
import top.wkbin.taixu.core.common.logging.CrashReporter
import top.wkbin.taixu.core.common.navigation.GlobalNavigationBus
import top.wkbin.taixu.core.common.translation.TranslationManager

/** Dependency registrations owned by the core:common module. */
val coreCommonModule = module {
    single<AppLogger> { AppLogger(context = get(), secretRedactor = get()) }

    single<CrashReporter> { CrashReporter(context = get(), secretRedactor = get()) }

    single<GlobalNavigationBus> { GlobalNavigationBus() }

    single<TranslationManager> {
        TranslationManager(context = get(), appLogger = get())
    }
}
