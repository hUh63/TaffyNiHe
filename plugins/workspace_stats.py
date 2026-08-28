# ============================================================
# 塔菲逆核官方示例插件 —— 工作区统计
# 市场名: workspace-stats
# 功能: 统计工作区文件数量/类型分布/总大小，逆向项目盘点利器
# ============================================================
meta = {
    "name": "工作区统计",
    "version": "1.0",
    "author": "taffy-official",
    "description": "统计工作区文件数量/类型分布/总大小",
    "source": "market",
}


def run(ext):
    ws = ext.workspace()
    if not ws:
        return "未设置工作区（设置→工作区）"
    files = ext.files()
    if not files:
        return "工作区为空"
    import os
    total = 0
    kinds = {}
    for f in files:
        p = os.path.join(ws, f)
        if os.path.isfile(p):
            size = os.path.getsize(p)
            total += size
            ext_name = f.rsplit(".", 1)[-1].lower() if "." in f else "无扩展名"
            kinds[ext_name] = kinds.get(ext_name, 0) + 1
    lines = [f"工作区: {ws}", f"文件数: {len(files)}  总大小: {total/1024:.1f} KB", "类型分布:"]
    for k, v in sorted(kinds.items(), key=lambda x: -x[1]):
        lines.append(f"  .{k}: {v} 个")
    return "\n".join(lines)
