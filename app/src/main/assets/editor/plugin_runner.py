#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
plugin_runner.py —— 塔菲扩展运行器。
用法: python3 plugin_runner.py <plugin.py>
流程: 注入 taffy_ext 到 sys.modules → 加载插件模块 → 调用 run(taffy_ext) → 打印返回值。
插件约定:
  meta = {"name": "...", "version": "1.0", "author": "...", "description": "..."}
  def run(ext):            # ext 即 taffy_ext 模块
      ...
      return "结果文本"    # 可返回 str / None
"""
import importlib.util
import json
import os
import sys


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
