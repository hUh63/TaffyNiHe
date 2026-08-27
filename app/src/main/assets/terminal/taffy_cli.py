#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
taffy_cli.py —— 塔菲逆核终端 CLI（MCP 客户端）
在终端会话里调用塔菲逆核的全部 MCP 工具：
  taffy_cli.py tools                        # 列出可用工具
  taffy_cli.py <tool> k=v k2=v2 ...         # 调用工具（值自动尝试 JSON 解析）
  taffy_cli.py <tool> --json '{"a":1}'      # 以 JSON 传参

依赖: 塔菲 MCP 服务已启动（设置→服务配置→启动服务），默认 http://127.0.0.1:8000/mcp
环境: TAFFY_URL / TAFFY_TOKEN 可覆盖
"""
import json
import os
import sys
import urllib.request

URL = os.environ.get("TAFFY_URL", "http://127.0.0.1:8000/mcp")
TOKEN = os.environ.get("TAFFY_TOKEN", "")


def rpc(method, params):
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params}).encode()
    req = urllib.request.Request(URL, data=body, headers={"Content-Type": "application/json"})
    if TOKEN:
        req.add_header("Authorization", "Bearer " + TOKEN)
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except Exception as e:
        sys.stderr.write("MCP 连接失败: %s\n提示: 请先在「设置 → 服务配置」启动 MCP 服务\n" % e)
        sys.exit(2)


def main():
    args = sys.argv[1:]
    if not args or args[0] in ("-h", "--help", "help"):
        print("taffy <tool> [k=v ...]  — 调用塔菲 MCP 工具")
        print("taffy tools             — 列出可用工具")
        print("taffy <tool> --json '{...}' — JSON 传参")
        print("示例: taffy taffy_so_open path=/sdcard/Download/lib.so")
        print("      taffy taffy_analyze_functions workspaceId=so-ws-xxx")
        print("      taffy taffy_linux action=shell distro=alpine command=uname -a")
        return
    if args[0] == "tools":
        r = rpc("tools/list", {})
        tools = (r.get("result") or {}).get("tools", [])
        for t in tools:
            print("%-36s %s" % (t.get("name", "?"), (t.get("description") or "")[:70]))
        print("共 %d 个工具" % len(tools))
        return
    name = args[0]
    arguments = {}
    rest = args[1:]
    if rest and rest[0] == "--json":
        arguments = json.loads(" ".join(rest[1:]))
    else:
        for a in rest:
            if "=" in a:
                k, v = a.split("=", 1)
                try:
                    v = json.loads(v)
                except Exception:
                    pass
                arguments[k] = v
            else:
                # 无 = 的参数按位置填入 (args/values 约定由工具决定)
                arguments.setdefault("args", []).append(a)
    r = rpc("tools/call", {"name": name, "arguments": arguments})
    if r.get("isError") or "error" in r:
        sys.stderr.write(json.dumps(r, ensure_ascii=False, indent=2) + "\n")
        sys.exit(1)
    content = r.get("content") or []
    for c in content:
        text = c.get("text", "")
        try:
            obj = json.loads(text)
            print(json.dumps(obj, ensure_ascii=False, indent=2)[:12000])
        except Exception:
            print(text[:12000])


if __name__ == "__main__":
    main()
