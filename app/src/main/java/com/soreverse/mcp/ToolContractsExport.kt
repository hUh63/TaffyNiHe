package com.soreverse.mcp

import com.soreverse.mcp.mcp.SchemaBuilder
import com.soreverse.mcp.mcp.ToolCatalog

/**
 * 借鉴 R2AIBridge 的「细粒度工具集 + SKILL.md」思路：把 App 内实时工具目录导出为
 * 一份 Markdown 契约文档（工具名 / 中英描述 / 分类 / 复杂度 / JSON Schema），
 * 可直接作为 AI 客户端的系统提示词或 SKILL.md 使用。
 *
 * 与静态的 MCP_SKILL 文案不同，本函数从 ToolCatalog.ALL 实时生成，工具增减后自动同步，
 * 不会与实现脱节。
 */
internal fun buildToolContractsMarkdown(zh: Boolean): String {
    val sb = StringBuilder()
    sb.append("# TaffyNiHe MCP Tools (SKILL)\n\n")
    val tools = ToolCatalog.ALL
    sb.append("共 ").append(tools.size).append(" 个工具 / total ").append(tools.size).append(" tools\n\n")
    sb.append("调用约定：JSON-RPC 2.0 `tools/call`，`arguments` 为 object；随工具不同可能含 `action` 等字段。\n\n")
    tools.forEach { h ->
        val m = h.meta
        sb.append("## ").append(m.name).append('\n')
        sb.append(if (zh) m.zh else m.en).append("\n\n")
        sb.append("- category: `").append(m.category).append("` · class: `").append(m.cls.name).append('`')
        if (m.heavy) sb.append(" · heavy")
        sb.append('\n')
        val schema = runCatching { m.schemaBuilder(SchemaBuilder) }.getOrNull()
        if (schema != null) {
            sb.append("\n```json\n").append(schema.toString(2)).append("\n```\n")
        }
        sb.append('\n')
    }
    return sb.toString()
}
