package top.wkbin.taixu.ui.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.wkbin.taixu.core.model.McpAuthMode
import top.wkbin.taixu.core.model.McpServerConfig
import top.wkbin.taixu.core.model.McpTransportType

/** 解析用户粘贴的各类 MCP JSON 格式 */
internal fun parseMcpJson(rawJson: String): Result<List<McpServerConfig>> = runCatching {
    val trimmed = rawJson.trim()
    if (trimmed.isBlank()) error("请输入 JSON 内容")
    val element = Json.parseToJsonElement(trimmed)
    val list = mutableListOf<McpServerConfig>()
    val rootObj = element.jsonObject

    if (rootObj.containsKey("mcpServers")) {
        val serversObj = rootObj["mcpServers"]?.jsonObject ?: error("mcpServers 不是有效的 JSON 对象")
        for ((serverName, serverVal) in serversObj) {
            val sObj = serverVal.jsonObject
            val url = sObj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val command = sObj["command"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val args = sObj["args"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            val env = sObj["env"]?.jsonObject?.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }.orEmpty()
            val desc = sObj["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val authMode = if (sObj["authMode"]?.jsonPrimitive?.contentOrNull.equals("OAUTH", ignoreCase = true)) McpAuthMode.OAUTH else McpAuthMode.NONE
            val oauthClientId = sObj["oauthClientId"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val oauthAuthorizationEndpoint = sObj["oauthAuthorizationEndpoint"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val oauthTokenEndpoint = sObj["oauthTokenEndpoint"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val oauthScope = sObj["oauthScope"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val oauthResource = sObj["oauthResource"]?.jsonPrimitive?.contentOrNull.orEmpty()

            val isSse = url.isNotBlank() || (command.isBlank() && sObj.containsKey("url"))
            list.add(
                McpServerConfig(
                    id = "custom_${serverName.lowercase().replace(Regex("[^a-z0-9_]"), "_")}_${System.currentTimeMillis()}",
                    name = serverName,
                    description = desc.ifBlank { if (isSse) "远程 HTTP 服务: $url" else "本地 Stdio: $command ${args.joinToString(" ")}" },
                    transportType = if (isSse) McpTransportType.SSE else McpTransportType.STDIO,
                    command = command,
                    args = args,
                    env = env,
                    serverUrl = url,
                    authMode = authMode,
                    oauthClientId = oauthClientId,
                    oauthAuthorizationEndpoint = oauthAuthorizationEndpoint,
                    oauthTokenEndpoint = oauthTokenEndpoint,
                    oauthScope = oauthScope,
                    oauthResource = oauthResource,
                    isEnabled = true,
                    isBuiltin = false,
                )
            )
        }
    } else {
        val name = rootObj["name"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "custom_mcp" }
        val url = rootObj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val command = rootObj["command"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val args = rootObj["args"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        val env = rootObj["env"]?.jsonObject?.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }.orEmpty()
        val desc = rootObj["description"]?.jsonPrimitive?.contentOrNull.orEmpty()

        val isSse = url.isNotBlank() || (command.isBlank() && rootObj.containsKey("url"))
        if (command.isBlank() && url.isBlank()) error("配置中必须包含 command 或 url 字段")
        list.add(
            McpServerConfig(
                id = "custom_${name.lowercase().replace(Regex("[^a-z0-9_]"), "_")}_${System.currentTimeMillis()}",
                name = name,
                description = desc.ifBlank { if (isSse) "远程 HTTP 服务: $url" else "本地 Stdio: $command ${args.joinToString(" ")}" },
                transportType = if (isSse) McpTransportType.SSE else McpTransportType.STDIO,
                command = command,
                args = args,
                env = env,
                serverUrl = url,
                isEnabled = true,
                isBuiltin = false,
            )
        )
    }
    if (list.isEmpty()) error("未找到有效的 MCP 服务配置")
    list
}
