package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.McpClientEndpoint
import com.soreverse.mcp.core.McpClientSession
import com.soreverse.mcp.core.McpClientStore
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 塔菲当 MCP 客户端：接别人的 MCP 服务端。
 *
 * 塔菲本身就是 MCP 服务端（/mcp + /sse），但很多时候工具在别处——PC 上的 frida 脚本服务、
 * 另一台设备的塔菲、局域网里的自建工具集。这个工具把这些端点管起来：
 * 存端点（含自定义 header）→ 测连通 → 列工具 → 调工具。
 *
 * 端点存在 SharedPreferences（taffy_mcp_client），与设置页共用同一份数据。
 */
object McpClientTool {

    val mcpClient: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_mcp_client",
            "【MCP 客户端】把外部 MCP 服务端接进来：action=endpoints 列已存端点；save 保存端点(name/url/headers，headers 每行 'K: V'，可放 Bearer token)；remove 删除；enable 启停；test 测连通与延迟；tools 列远端工具；call 调用远端工具(tool + arguments)。支持 Streamable HTTP 与 SSE 响应。",
            "Act as an MCP client for external MCP servers: endpoints/save/remove/enable/test/tools/call over Streamable HTTP + SSE.",
            "dynamic", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf("endpoints (default) | save | remove | enable | test | tools | call", "endpoints", "save", "remove", "enable", "test", "tools", "call")
                "id" str "save/remove/enable 用：端点 id（留空则按 url 生成）"
                "name" str "save 用：显示名"
                "url" str "save 用：MCP 端点地址，如 http://192.168.1.5:8971/mcp"
                "headers" str "save 用：自定义请求头，每行一个 'K: V'"
                "endpoint" str "test/tools/call 用：端点 id 或 url（留空=第一个启用的）"
                "enabled" str "enable 用：true/false"
                "tool" str "call 用：远端工具名"
                "arguments" str "call 用：参数 JSON 字符串，如 {\"path\":\"/sdcard/a.apk\"}"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val action = args.str("action", "endpoints").ifBlank { "endpoints" }
            val c = ctx.context
            return when (action) {
                "endpoints" -> {
                    val list = McpClientStore.load(c)
                    ok(JSONObject()
                        .put("action", "endpoints")
                        .put("count", list.size)
                        .put("endpoints", JSONArray().apply {
                            for (e in list) put(JSONObject()
                                .put("id", e.id)
                                .put("name", e.name)
                                .put("url", e.url)
                                .put("enabled", e.enabled)
                                .put("hasHeaders", e.headers.isNotBlank()))
                        })
                        .put("next", "test endpoint=<id> 测连通；tools 列工具；call tool=<name> arguments={...}"))
                }

                "save" -> {
                    val url = args.str("url").trim()
                    if (url.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 url", "url", "")
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        return err("INVALID_ARGUMENT", "url 必须以 http:// 或 https:// 开头", "url", url)
                    }
                    val id = args.str("id").ifBlank { "ep-" + UUID.randomUUID().toString().take(8) }
                    val ep = McpClientEndpoint(
                        id = id,
                        name = args.str("name").ifBlank { url },
                        url = url,
                        headers = args.str("headers"),
                        enabled = args.str("enabled", "true").ifBlank { "true" }.toBoolean(),
                    )
                    McpClientStore.upsert(c, ep)
                    ok(JSONObject().put("action", "save").put("id", ep.id).put("url", ep.url).put("name", ep.name))
                }

                "remove" -> {
                    val id = args.str("id").ifBlank { args.str("url") }
                    if (id.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 id", "id", "")
                    val removed = McpClientStore.remove(c, id)
                    if (removed) ok(JSONObject().put("action", "remove").put("id", id))
                    else err("NOT_FOUND", "没有这个端点: $id", "id", id)
                }

                "enable" -> {
                    val id = args.str("id").ifBlank { args.str("url") }
                    if (id.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 id", "id", "")
                    val enabled = args.str("enabled", "true").ifBlank { "true" }.toBoolean()
                    if (McpClientStore.setEnabled(c, id, enabled)) {
                        ok(JSONObject().put("action", "enable").put("id", id).put("enabled", enabled))
                    } else {
                        err("NOT_FOUND", "没有这个端点: $id", "id", id)
                    }
                }

                "test" -> {
                    val ep = McpClientStore.resolve(c, args.str("endpoint")) 
                        ?: return err("ENDPOINT_NOT_FOUND", "未找到端点（先在 save 里存一个，或用 endpoint 指定 id/url）")
                    val started = System.currentTimeMillis()
                    val session = McpClientSession(ep)
                    val okInit = session.ping()
                    val cost = System.currentTimeMillis() - started
                    if (okInit) {
                        ok(JSONObject().put("action", "test").put("endpoint", ep.name).put("url", ep.url)
                            .put("latencyMs", cost).put("session", session.sessionId ?: "(无)"))
                    } else {
                        err("CONNECT_FAILED", "连接失败（${ep.url}）：${session.lastError}", "endpoint", ep.id)
                    }
                }

                "tools" -> {
                    val ep = McpClientStore.resolve(c, args.str("endpoint"))
                        ?: return err("ENDPOINT_NOT_FOUND", "未找到端点")
                    val session = McpClientSession(ep)
                    val tools = session.listTools()
                        ?: return err("LIST_FAILED", "拉取工具列表失败：${session.lastError}", "endpoint", ep.id)
                    val out = JSONArray()
                    for (i in 0 until tools.length()) {
                        val t = tools.optJSONObject(i) ?: continue
                        val schema = t.optJSONObject("inputSchema")
                        val props = schema?.optJSONObject("properties")
                        val keys = ArrayList<String>()
                        if (props != null) {
                            val names = props.keys()
                            while (names.hasNext()) keys.add(names.next())
                        }
                        out.put(JSONObject()
                            .put("name", t.optString("name"))
                            .put("description", t.optString("description").take(300))
                            .put("required", schema?.optJSONArray("required") ?: JSONArray())
                            .put("arguments", JSONArray().apply { for (k in keys) put(k) }))
                    }
                    ok(JSONObject()
                        .put("action", "tools")
                        .put("endpoint", ep.name)
                        .put("count", out.length())
                        .put("tools", out)
                        .put("next", "call tool=<name> arguments={...}"))
                }

                "call" -> {
                    val tool = args.str("tool").ifBlank { args.str("name") }
                    if (tool.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 tool", "tool", "")
                    val ep = McpClientStore.resolve(c, args.str("endpoint"))
                        ?: return err("ENDPOINT_NOT_FOUND", "未找到端点")
                    val rawArgs = args.str("arguments").ifBlank { "{}" }
                    val arguments = runCatching { JSONObject(rawArgs) }.getOrElse {
                        return err("INVALID_ARGUMENT", "arguments 不是合法 JSON: $rawArgs", "arguments", rawArgs)
                    }
                    val session = McpClientSession(ep)
                    val text = session.callTool(tool, arguments)
                        ?: return err("CALL_FAILED", "调用 $tool 失败：${session.lastError}", "tool", tool)
                    ok(JSONObject()
                        .put("action", "call")
                        .put("endpoint", ep.name)
                        .put("tool", tool)
                        .put("text", text.take(200_000))
                        .put("length", text.length))
                }

                else -> err("INVALID_ARGUMENT", "未知 action: $action", "action", action)
            }
        }
    }

    val ALL = listOf(mcpClient)
}
