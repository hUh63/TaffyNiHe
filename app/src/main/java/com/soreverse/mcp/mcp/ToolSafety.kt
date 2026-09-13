package com.soreverse.mcp.mcp

/**
 * 「破坏性工具」判定（借鉴 fler 的 destructive-tools 思路）。
 *
 * 塔菲的 edit / patch / smali / 签名 等写操作是核心逆向能力，因此**默认不拦截**
 * （`SettingsStore.blockDestructiveTools` 默认 false，不改变原有工作流）。
 * 用户如需给未经确认的 MCP 客户端加一道闸，可在「编辑校验与审计」页主动开启；
 * 开启后 [com.soreverse.mcp.mcp.McpHttpServer] 会拒绝本对象判定为破坏性的工具调用。
 *
 * 判定为启发式（工具名关键词），设置页会展示实际会被拦截的工具名，避免黑箱。
 */
object ToolSafety {

    /** 只读语义关键词：命中即**不**视为破坏性（如 *_check / *_info / *_list / *_dump / *_diff）。 */
    private val READONLY_HINTS = listOf(
        "check", "info", "list", "get", "read", "scan", "probe", "status", "find",
        "query", "xref", "analyze", "disasm", "strings", "dump", "view", "preview",
        "diff", "compare", "search", "detect", "verify", "test", "hint", "help", "describe",
    )

    /** 写 / 删语义关键词：命中（且未命中只读关键词）即视为破坏性。 */
    private val WRITE_HINTS = listOf(
        "edit", "write", "patch", "delete", "remove", "kill", "rename", "shrink",
        "install", "uninstall", "rollback", "clear", "sign", "inject", "nop", "hook",
        "repack", "manifest_xml", "smali", "restore",
    )

    private fun normalize(name: String): String =
        name.lowercase().removePrefix("taffy_").removePrefix("mcp2_").removePrefix("mcp_")

    /** 是否破坏性工具（写 / 删 / 签名 / 注入等）。 */
    fun isDestructive(name: String): Boolean {
        val n = normalize(name)
        if (n.isBlank()) return false
        if (READONLY_HINTS.any { n.contains(it) }) return false
        return WRITE_HINTS.any { n.contains(it) }
    }

    /** 当前目录中被判定为破坏性的工具名（供设置页展示，排序稳定）。 */
    fun destructiveToolNames(): List<String> =
        ToolCatalog.ALL.map { it.meta.name }.filter { isDestructive(it) }.sorted()
}
