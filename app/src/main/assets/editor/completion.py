#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
completion.py —— 塔菲编辑器 jedi 补全服务（完整版代码智能）。
输入(stdin JSON): {"code": "...", "line": 1, "col": 1, "kind": "complete|hover", "path": null}
输出(stdout JSON): [{"name", "type", "doc"}...]
能力: complete（补全候选）/ hover（悬停文档与类型推断）/ defs（跳转定义位置）
"""
import json
import sys


def emit(items):
    sys.stdout.write(json.dumps(items, ensure_ascii=False))
    sys.stdout.flush()


def main():
    try:
        data = json.loads(sys.stdin.read() or "{}")
    except Exception:
        emit([])
        return
    code = data.get("code", "") or ""
    line = int(data.get("line", 1))
    col = int(data.get("col", 1))
    kind = data.get("kind", "complete")
    path = data.get("path")
    if not code.strip():
        emit([])
        return
    try:
        import jedi
    except Exception as e:
        emit([{"name": "__error__", "type": "error", "doc": "jedi 不可用: %s" % e}])
        return
    try:
        script = jedi.Script(code=code, path=path)
        items = []
        if kind == "hover":
            for d in script.infer(line, col)[:6]:
                try:
                    doc = d.docstring()[:500]
                except Exception:
                    doc = ""
                items.append({"name": d.name, "type": d.type, "doc": doc})
        elif kind == "defs":
            for d in script.goto(line, col, follow_imports=True)[:10]:
                items.append({
                    "name": d.name, "type": d.type,
                    "module": getattr(d, "module_name", "") or "",
                    "line": d.line or 0, "col": d.column or 0,
                    "doc": "定义于 %s:%s" % (getattr(d, "module_name", "?") or "?", d.line or "?"),
                })
        else:
            for c in script.complete(line, col)[:40]:
                try:
                    doc = c.docstring()[:200]
                except Exception:
                    doc = ""
                items.append({"name": c.name, "type": c.type, "doc": doc})
        emit(items)
    except Exception as e:
        emit([{"name": "__error__", "type": "error", "doc": str(e)}])


main()
