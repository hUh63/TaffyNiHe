package top.wkbin.taixu.di

import org.koin.dsl.module
import top.wkbin.taixu.di.feature.navigation.navigationModule
import top.wkbin.taixu.di.app.appModule
import top.wkbin.taixu.di.core.common.coreCommonModule
import top.wkbin.taixu.di.core.database.coreDatabaseModule
import top.wkbin.taixu.di.core.datastore.coreDatastoreModule
import top.wkbin.taixu.di.core.network.coreNetworkModule
import top.wkbin.taixu.di.core.security.coreSecurityModule
import top.wkbin.taixu.di.feature.onboarding.featureOnboardingModule
import top.wkbin.taixu.di.harness.harnessModule
import top.wkbin.taixu.di.harness.botHarnessModule
import top.wkbin.taixu.di.project.template.projectTemplateModule
import top.wkbin.taixu.di.runtime.runtimeModule
import top.wkbin.taixu.di.runtime.browser.runtimeBrowserModule
import top.wkbin.taixu.di.tools.toolsModule

/** Complete process graph; Android context is supplied at application startup. */
val taiXuModule = module {
    includes(
        navigationModule,
        appModule,
        coreCommonModule,
        coreDatabaseModule,
        coreDatastoreModule,
        coreNetworkModule,
        coreSecurityModule,
        featureOnboardingModule,
        harnessModule,
        botHarnessModule,
        projectTemplateModule,
        runtimeModule,
        runtimeBrowserModule,
        toolsModule,
    )
}
