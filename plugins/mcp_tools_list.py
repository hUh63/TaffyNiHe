# ============================================================
# 塔菲逆核官方示例插件 —— MCP 工具速查
# 市场名: mcp-tools-list
# 功能: 列出塔菲全部 MCP 工具名与描述，写插件前先跑这个了解能力面
# ============================================================
meta = {
    "name": "MCP 工具速查",
    "version": "1.0",
    "author": "taffy-official",
    "description": "列出塔菲全部 MCP 工具（写插件的必备参考）",
    "source": "market",
}


def run(ext):
    try:
        tools = ext.tools()
    except Exception as e:
        return f"无法连接 MCP 服务（请先在服务配置启动）: {e}"
    if not tools:
        return "MCP 服务未返回工具列表"
    lines = [f"共 {len(tools)} 个工具:", ""]
    for t in tools:
        lines.append(f"  {t['name']}")
        if t["description"]:
            lines.append(f"      {t['description'][:80]}")
    lines.append("")
    lines.append("调用示例: ext.mcp(\"<工具名>\", 参数名=值)")
    return "\n".join(lines)
