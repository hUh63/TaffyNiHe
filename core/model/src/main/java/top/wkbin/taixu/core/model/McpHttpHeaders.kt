package top.wkbin.taixu.harness.mcp

import top.wkbin.taixu.core.model.BuiltinMcpPresets
import top.wkbin.taixu.core.model.McpAuthMode
import top.wkbin.taixu.core.model.McpServerConfig
import top.wkbin.taixu.core.model.validateMcpHttpHeaders
import top.wkbin.taixu.harness.mcp.oauth.McpOAuthTokenProvider
import top.wkbin.taixu.harness.mcp.server.BuiltinBrowserMcpAccess

internal suspend fun resolveMcpHttpHeaders(server: McpServerConfig, oauth: McpOAuthTokenProvider?): Map<String, String> {
    validateMcpHttpHeaders(server.headers)
    val headers = server.headers.toMutableMap()
    val authorization = headers.keys.firstOrNull { it.equals("Authorization", ignoreCase = true) }
    require(authorization == null || server.authMode != McpAuthMode.OAUTH) { "自定义 Authorization 与 OAuth 不能同时启用" }
    val token = server.authToken.takeIf { it.isNotBlank() }
        ?: if (server.id == BuiltinMcpPresets.BROWSER_BUILTIN_ID) BuiltinBrowserMcpAccess.token
        else if (authorization == null) oauth?.accessToken(server) else null
    if (!token.isNullOrBlank()) {
        authorization?.let(headers::remove)
        headers["Authorization"] = "Bearer $token"
    }
    validateMcpHttpHeaders(headers)
    return headers
}
