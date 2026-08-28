#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
plugin_runner.py —— 塔菲扩展运行器（含插件沙箱）。
用法: python3 plugin_runner.py <plugin.py>
流程: 装沙箱审计钩子 → 注入 taffy_ext → 加载插件 → 调 run(ext) → 打印返回值。

沙箱（PEP 578 sys.addaudithook，环境变量 TAFFY_SANDBOX=1 启用）:
  ✗ 禁外网    socket.connect 仅放行 TAFFY_SANDBOX_NET 列表（默认只有塔菲 MCP 端口）
  ✗ 禁子进程  subprocess.Popen / os.exec* / os.fork / os.spawn 一律拒绝
  ✓ 文件白名单 读全放行；写/删/改名 仅限 TAFFY_SANDBOX_WRITE 目录（工作区+插件目录）
违规即抛 RuntimeError，插件崩溃并在输出区显示拒绝原因。
"""
import importlib.util
import json
import os
import sys


def install_sandbox():
    import sys as _sys
    import os as _os

    write_roots = [_os.path.abspath(p) for p in _os.environ.get("TAFFY_SANDBOX_WRITE", "").split(_os.pathsep) if p]
    net_allow = []
    for a in _os.environ.get("TAFFY_SANDBOX_NET", "").split(_os.pathsep):
        if ":" in a:
            h, p = a.rsplit(":", 1)
            try:
                net_allow.append((h, int(p)))
            except ValueError:
                pass

    def deny(msg):
        raise RuntimeError("沙箱拒绝: " + msg)

    def in_write_roots(p):
        np = _os.path.abspath(p)
        for r in write_roots:
            rr = r.rstrip("/")
            if np == rr or np.startswith(rr + "/"):
                return True
        return False

    def hook(event, args):
        if event == "open":
            path, mode = args[0], args[1] or "r"
            if isinstance(path, str) and mode and any(m in mode for m in "wxa+"):
                if not in_write_roots(path):
                    deny(f"写入 {path}（白名单外；允许: 工作区/插件目录）")
        elif event == "socket.connect":
            addr = args[0]
            if isinstance(addr, tuple) and len(addr) == 2:
                host, port = addr[0], addr[1]
                for h, p in net_allow:
                    if str(host) == str(h) and int(port) == int(p):
                        return
                deny(f"网络连接 {host}:{port}（仅允许塔菲 MCP 端口）")
            else:
                deny(f"网络连接（受限通道: {addr!r}）")
        elif event in ("subprocess.Popen", "os.exec", "os.fork", "os.spawn"):
            deny(f"禁止启动子进程（{event}）")
        elif event in ("os.remove", "os.rename", "os.rmdir", "os.unlink"):
            path = args[0]
            if isinstance(path, str) and not in_write_roots(path):
                deny(f"删除/改名 {path}（白名单外）")
        elif event == "os.system":
            deny("禁止 os.system")
        # 其余事件（读文件/import/getaddrinfo 等）放行

    _sys.addaudithook(hook)


def main():
    if len(sys.argv) < 2:
        print("[runner] 缺少插件路径参数")
        return 2
    plugin_path = os.path.abspath(sys.argv[1])
    if not os.path.isfile(plugin_path):
        print("[runner] 插件不存在: %s" % plugin_path)
        return 2
    support = os.environ.get("TAFFY_SUPPORT", "")
    plug_dir = os.path.dirname(plugin_path)

    sandbox_on = os.environ.get("TAFFY_SANDBOX") == "1"
    if sandbox_on:
        try:
            install_sandbox()
            print("[sandbox] 已启用: 禁外网(仅MCP) / 禁子进程 / 写白名单")
        except Exception as e:
            print("[sandbox] 启用失败（继续但无沙箱）: %s" % e)

    # 注入 taffy_ext（支持脚本目录）—— 插件 import taffy_ext 即可用宿主能力
    try:
        if support and support not in sys.path:
            sys.path.insert(0, support)
        import taffy_ext
        sys.modules["taffy_ext"] = taffy_ext
    except Exception as e:
        print("[runner] taffy_ext 加载失败: %s" % e)
        return 2

    sys.path.insert(0, plug_dir)
    os.chdir(plug_dir)
    spec = importlib.util.spec_from_file_location("taffy_plugin", plugin_path)
    try:
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
    except SystemExit as e:
        print("[runner] 插件调用 sys.exit(%s)" % e)
        return 0
    except RuntimeError as e:
        if sandbox_on and str(e).startswith("沙箱拒绝"):
            import traceback
            print("[runner] 插件触发沙箱拦截:\n%s\n%s" % (e, "".join(traceback.format_exc().splitlines(keepends=True)[-4:])))
            return 3
        raise
    except Exception as e:
        import traceback
        print("[runner] 插件加载失败:\n%s" % traceback.format_exc())
        return 1

    fn = getattr(mod, "run", None)
    if not callable(fn):
        print("[runner] 插件缺少 run(ext) 入口函数。约定:\n"
              "  def run(ext):\n      ext.log('hello')\n      return '输出'")
        return 1
    try:
        ret = fn(taffy_ext)
    except RuntimeError as e:
        if sandbox_on and str(e).startswith("沙箱拒绝"):
            import traceback
            print("[runner] 插件触发沙箱拦截:\n%s\n%s" % (e, "".join(traceback.format_exc().splitlines(keepends=True)[-4:])))
            return 3
        raise
    except Exception as e:
        import traceback
        print("[runner] 插件 run() 执行异常:\n%s" % traceback.format_exc())
        return 1
    if ret is not None:
        if isinstance(ret, (dict, list)):
            print(json.dumps(ret, ensure_ascii=False, indent=2))
        else:
            print(str(ret))
    print("[runner] 完成")
    return 0


sys.exit(main())
