package top.wkbin.taixu.runtime.browser.di

import top.wkbin.taixu.runtime.browser.BrowserEventBus
import top.wkbin.taixu.runtime.browser.BrowserRegistry
import top.wkbin.taixu.runtime.browser.BrowserRegistryImpl

object BrowserModule {
    fun provideEventBus(): BrowserEventBus = BrowserEventBus()

    fun provideRegistry(eventBus: BrowserEventBus): BrowserRegistry = BrowserRegistryImpl(eventBus)
}
