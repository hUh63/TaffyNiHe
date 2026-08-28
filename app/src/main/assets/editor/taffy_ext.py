#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
taffy_ext.py —— 塔菲逆核扩展 API（完整宿主能力，插件 import 即用）。

环境变量（由 plugin_runner.py 注入）:
  TAFFY_WORKSPACE   工作区绝对路径
  TAFFY_PLUGIN_DIR  当前插件目录
  TAFFY_MCP_URL     MCP 端点（http://127.0.0.1:8000/mcp）
  TAFFY_TOKEN       访问令牌
  TAFFY_SUPPORT     支持脚本目录（含 taffy_cli.py）

API 一览:
  log(*args)                    输出日志（显示在扩展页输出区）
  workspace()                   工作区路径
  plugin_dir()                  当前插件目录
  files(sub="")                 列出工作区文件/子目录
  read(path)                    读工作区文本文件
  write(path, data)             写工作区文本文件（返回绝对路径）
  mcp(tool, **kwargs)           调用塔菲全部 MCP 工具（工作区/终端/rizin/eDBG/抓包...）
  tools()                       列出可用 MCP 工具
  env(key, default=None)        读取宿主注入环境变量
"""
import json
import os
import sys

_workspace = os.environ.get("TAFFY_WORKSPACE", "")
_plugin_dir = os.environ.get("TAFFY_PLUGIN_DIR", "")
_mcp_url = os.environ.get("TAFFY_MCP_URL", "http://127.0.0.1:8000/mcp")
_token = os.environ.get("TAFFY_TOKEN", "")
_support = os.environ.get("TAFFY_SUPPORT", "")


def log(*args):
    print("[taffy_ext]", *args, flush=True)


def workspace():
    return _workspace


def plugin_dir():
    return _plugin_dir


def env(key, default=None):
    return os.environ.get(key, default)


def _resolve(path):
    """相对路径锚定工作区（防越界由宿主端 plugin_runner 兜底校验）。"""
    if os.path.isabs(path):
        return path
    return os.path.join(_workspace, path) if _workspace else path


def files(sub=""):
    base = _resolve(sub) if sub else _workspace
    try:
        return sorted(os.listdir(base))
    except Exception:
        return []


def read(path):
    with open(_resolve(path), "r", encoding="utf-8", errors="replace") as f:
        return f.read()


def write(path, data):
    p = _resolve(path)
    d = os.path.dirname(p)
    if d:
        os.makedirs(d, exist_ok=True)
    with open(p, "w", encoding="utf-8") as f:
        f.write(data)
    return p


def _cli():
    """加载 taffy_cli（MCP 客户端）并覆盖其连接配置。"""
    if _support and _support not in sys.path:
        sys.path.insert(0, _support)
    import taffy_cli
    taffy_cli.URL = _mcp_url
    taffy_cli.TOKEN = _token
    return taffy_cli


def mcp(tool, **kwargs):
    """调用塔菲逆核 MCP 工具，返回文本结果（内容块拼接）。"""
    cli = _cli()
    resp = cli.rpc("tools/call", {"name": tool, "arguments": kwargs})
    if resp.get("error"):
        raise RuntimeError("MCP 错误: %s" % resp["error"])
    result = resp.get("result", {})
    if result.get("isError"):
        raise RuntimeError("工具返回错误")
    parts = []
    for c in result.get("content", []):
        if c.get("type") == "text":
            parts.append(c.get("text", ""))
    return "\n".join(parts)


def tools():
    """列出可用 MCP 工具名与描述。"""
    cli = _cli()
    resp = cli.rpc("tools/list", {})
    out = []
    for t in resp.get("result", {}).get("tools", []):
        out.append({"name": t.get("name", ""), "description": (t.get("description", "") or "")[:120]})
    return out
