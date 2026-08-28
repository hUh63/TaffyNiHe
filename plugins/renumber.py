# ============================================================
# 塔菲逆核官方示例插件 —— 行号标注
# 市场名: renumber
# 功能: 给工作区指定文本文件每行加行号（原文件自动留备份由编辑器负责）
# ============================================================
meta = {
    "name": "行号标注",
    "version": "1.0",
    "author": "taffy-official",
    "description": "给工作区文本文件每行加行号，输出到报告文件",
    "source": "market",
}


def run(ext):
    ws = ext.workspace()
    if not ws:
        return "未设置工作区"
    out = []
    for f in ext.files():
        if not f.endswith((".py", ".sh", ".json", ".txt", ".smali", ".xml")):
            continue
        try:
            text = ext.read(f)
        except Exception:
            continue
        numbered = "".join("%5d  %s\n" % (i + 1, ln) for i, ln in enumerate(text.splitlines()))
        report = "renumber_" + f.rsplit("/", 1)[-1] + ".txt"
        ext.write(report, numbered)
        out.append(f"{f} -> {report}")
    if not out:
        return "没有可处理的文本文件（.py/.sh/.json/.txt/.smali/.xml）"
    return "已生成行号文件:\n" + "\n".join(out)
