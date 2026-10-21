package top.wkbin.taixu.di.harness

import org.koin.dsl.module
import top.wkbin.taixu.core.tools.ExternalProviderProbe
import top.wkbin.taixu.harness.LlmApiRegistry
import top.wkbin.taixu.harness.ProviderClient
import top.wkbin.taixu.harness.ProviderModelResolver
import top.wkbin.taixu.harness.ProviderTransport
import top.wkbin.taixu.harness.plugins.ExternalHarnessPlugins
import top.wkbin.taixu.harness.plugins.ExternalProviderConnectionProbe
import top.wkbin.taixu.harness.plugins.HarnessPlugin
import top.wkbin.taixu.harness.plugins.HarnessPluginRegistry
import top.wkbin.taixu.harness.plugins.builtinHarnessPlugins

internal val providerModule = module {
    single<ExternalProviderProbe> { ExternalProviderConnectionProbe(get(), get(), get()) }
    single { ExternalHarnessPlugins(get(), get()) }
    single { val external = get<ExternalHarnessPlugins>()
        HarnessPluginRegistry(builtinHarnessPlugins() + getAll<HarnessPlugin>(), external::plugins)
    }
    single { ProviderModelResolver(providerRepository = get(), modelDao = get(), settingsDataStore = get()) }
    single { val plugins = get<HarnessPluginRegistry>(); val json = get<kotlinx.serialization.json.Json>()
        ProviderTransport(client = get(), json = json, adapters = { client ->
            LlmApiRegistry(
                plugins.providers(
                    client,
                    json
                ), liveOverrides = { plugins.providerOverrides(client, json) })
        }) }
    single { ProviderClient(modelResolver = get(), transport = get()) }
}
