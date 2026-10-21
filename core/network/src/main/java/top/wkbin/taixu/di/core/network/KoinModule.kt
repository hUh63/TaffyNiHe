package top.wkbin.taixu.di.core.network

import org.koin.dsl.module
import top.wkbin.taixu.core.network.AppUpdateManager
import top.wkbin.taixu.core.network.CcSwitchClient
import top.wkbin.taixu.core.network.ChecksumVerifier
import top.wkbin.taixu.core.network.HttpClientProvider
import top.wkbin.taixu.core.network.ResumableFileDownloader

/** Dependency registrations owned by the core:network module. */
val coreNetworkModule = module {
    single<AppUpdateManager> { AppUpdateManager(context = get(), httpClient = get()) }

    single<CcSwitchClient> { CcSwitchClient(httpClientProvider = get()) }

    single<ChecksumVerifier> { ChecksumVerifier() }

    single<ResumableFileDownloader> { ResumableFileDownloader(httpClient = get(), checksumVerifier = get()) }

    single<HttpClientProvider> { HttpClientProvider() }
}
