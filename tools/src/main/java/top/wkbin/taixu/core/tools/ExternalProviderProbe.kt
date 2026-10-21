package top.wkbin.taixu.core.tools

/** Implemented by Harness; the tools module never dispatches plugin requests as HTTP. */
fun interface ExternalProviderProbe {
    suspend fun test(providerId: String, model: String)
}
