#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
架构门禁 (policy-driven architecture gate)
=========================================

对 app/src/main/java/ 下所有 .kt 文件做纯静态扫描, 四类策略检查:

  1. 文件尺寸棘轮  单文件 > 4000 行 WARN, > 8000 行 ERROR;
                   基线里已登记的文件只要行数不再增长就不报 ERROR。
  2. 包依赖无环    package / import 构建包级有向图, DFS 找环; 有环 ERROR。
  3. 分层依赖方向  core(0) < engine(1) < mcp(2) < ui/根包(3);
                   低层 import 高层 -> ERROR。
  4. 禁止事项      TODO(/FIXME( 单文件 > 20 处 -> WARN; println( -> WARN。

历史包袱由 tools/arch-baseline.json 承载(自举): 首次运行自动把当前
超限文件 / 已有环 / 已有跨层依赖登记为基线, 不让存量问题直接红掉 CI,
但绝不允许继续变胖/新增。

用法:
  python3 tools/arch-policy.py                  # 检查 (仓库根执行)
  python3 tools/arch-policy.py --update-baseline  # 重新登记当前状态为基线
  python3 tools/arch-policy.py --root .          # 显式指定仓库根

退出码: 有 ERROR -> 1; 只有 WARN -> 0。
不依赖第三方库, 不调用 git。
"""

import json
import os
import sys
from datetime import datetime, timezone

# ---------------------------------------------------------------- 策略常量 --

SRC_DIR = "app/src/main/java"
BASELINE_PATH = "tools/arch-baseline.json"

MAX_WARN_LINES = 4000      # 单文件行数 WARN 阈值
MAX_ERROR_LINES = 8000     # 单文件行数 ERROR 阈值
MAX_TODO_PER_FILE = 20     # TODO(/FIXME( 单文件密度阈值

ROOT_PKG = "com.soreverse.mcp"

# 层次数字越小越底层; 低层不得 import 高层。
PKG_LAYERS = {
    ROOT_PKG + ".core": 0,
    ROOT_PKG + ".engine": 1,
    ROOT_PKG + ".mcp": 2,
}
# 根包 / 根包下的其它直接子包 (service, nativecore, blutter, ui ...) 一律视作 ui 层。
UI_LAYER = 3

LAYER_NAMES = {0: "core", 1: "engine", 2: "mcp", 3: "ui"}

# println( 检查的排除前缀 (仓库根相对路径)。
PRINTLN_EXCLUDE_PREFIXES = ("tools/", "app/src/test/", "app/src/androidTest/")

BASELINE_VERSION = 1


# ------------------------------------------------------------------ 工具函数 --

def read_text(path):
    """读文件, 容忍 BOM / 非 UTF-8 / CRLF。"""
    with open(path, "rb") as fh:
        raw = fh.read()
    text = raw.decode("utf-8", errors="replace")
    if text.startswith("\ufeff"):
        text = text[1:]
    return text.replace("\r\n", "\n").replace("\r", "\n")


def layer_of(pkg):
    """返回包所属层次编号; 非本项目包返回 None (不参与方向检查)。"""
    if pkg == ROOT_PKG:
        return UI_LAYER
    if not pkg.startswith(ROOT_PKG + "."):
        return None
    head = pkg[len(ROOT_PKG) + 1:].split(".")[0]
    return PKG_LAYERS.get(ROOT_PKG + "." + head, UI_LAYER)


def canonical_cycle(nodes):
    """把环旋转到字典序最小的节点开头, 保证同一条环只出现一次。"""
    if not nodes:
        return ""
    n = len(nodes)
    best = None
    for i in range(n):
        rotated = tuple(nodes[i:] + nodes[:i])
        if best is None or rotated < best:
            best = rotated
    return " -> ".join(list(best) + [best[0]])


# -------------------------------------------------------------------- 扫描 --

class Finding(object):
    __slots__ = ("severity", "path", "line", "message", "category")

    def __init__(self, severity, path, line, message, category):
        self.severity = severity      # "ERROR" / "WARN"
        self.path = path
        self.line = line
        self.message = message
        self.category = category

    def render(self):
        return "%s\t%s:%s\t%s" % (self.severity, self.path, self.line, self.message)

    def sort_key(self):
        return (0 if self.severity == "ERROR" else 1, self.path, int(self.line or 0))


def collect_files(root):
    """收集 app/src/main/java 下所有 .kt 文件 (返回 [(相对路径, 绝对路径)])。"""
    base = os.path.join(root, SRC_DIR)
    out = []
    for dirpath, dirnames, filenames in os.walk(base):
        dirnames[:] = sorted(d for d in dirnames if d not in (".git", "build"))
        for name in sorted(filenames):
            if not name.endswith(".kt"):
                continue
            abs_path = os.path.join(dirpath, name)
            rel = os.path.relpath(abs_path, root).replace(os.sep, "/")
            out.append((rel, abs_path))
    return sorted(out)


def scan_file(rel, abs_path):
    """解析单个 .kt 文件, 返回结构化的扫描结果。"""
    text = read_text(abs_path)
    lines = text.split("\n")
    if lines and lines[-1] == "":
        lines = lines[:-1]
    line_count = len(lines)

    pkg = None
    imports = []          # [(目标包名, 行号)]  仅本项目 import
    todo_hits = []        # 行号
    println_hits = []     # 行号
    expect_pkg = None

    for idx, line in enumerate(lines, 1):
        stripped = line.strip()

        if pkg is None and stripped.startswith("package "):
            pkg = stripped[len("package "):].strip().rstrip(";").strip()
            continue

        if stripped.startswith("import "):
            target = stripped[len("import "):].strip().rstrip(";").strip()
            if target.startswith("import "):
                target = target[len("import "):].strip()
            alias = target.split(" as ")[0].strip()
            if alias.endswith(".*"):
                alias = alias[:-2]
            if alias == ROOT_PKG or alias.startswith(ROOT_PKG + "."):
                imports.append((alias, idx))
            continue

        if "TODO(" in line or "FIXME(" in line:
            todo_hits.append(idx)

        if "println(" in line:
            # 排除纯注释行, 降低误报。
            if not (stripped.startswith("//") or stripped.startswith("*")
                    or stripped.startswith("/*")):
                println_hits.append(idx)

    return {
        "path": rel,
        "package": pkg,
        "lines": line_count,
        "imports": imports,
        "todo_hits": todo_hits,
        "println_hits": println_hits,
    }


def resolve_import_package(symbol, known_packages):
    """把 import 的完整符号解析成包名: 取已知包的最长前缀。"""
    if symbol in known_packages:
        return symbol
    parts = symbol.split(".")
    for cut in range(len(parts) - 1, 0, -1):
        cand = ".".join(parts[:cut])
        if cand in known_packages:
            return cand
    return None


# --------------------------------------------------------------- 基线读写 --

def empty_baseline():
    return {
        "version": BASELINE_VERSION,
        "generated": None,
        "note": "架构门禁基线: 存量债务登记表, 只允许收缩不允许扩张。",
        "oversizeFiles": {},
        "allowedCycles": [],
        "allowedLayerViolations": [],
        "thresholds": {
            "maxWarnLines": MAX_WARN_LINES,
            "maxErrorLines": MAX_ERROR_LINES,
            "maxTodoPerFile": MAX_TODO_PER_FILE,
        },
    }


def load_baseline(root):
    """返回 (baseline_dict, 是否新生成, 是否有告警)。"""
    path = os.path.join(root, BASELINE_PATH)
    if not os.path.exists(path):
        return empty_baseline(), True, None
    try:
        with open(path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
        if not isinstance(data, dict):
            raise ValueError("基线根节点不是 JSON 对象")
    except Exception as exc:  # noqa: BLE001 - 基线损坏不该让门禁崩掉
        return empty_baseline(), False, "基线文件无法解析 (%s), 本次按空基线处理" % exc

    base = empty_baseline()
    base["version"] = data.get("version", BASELINE_VERSION)
    base["generated"] = data.get("generated")
    over = data.get("oversizeFiles") or {}
    if isinstance(over, dict):
        base["oversizeFiles"] = {str(k): int(v) for k, v in over.items() if str(k)}
    for key in ("allowedCycles", "allowedLayerViolations"):
        val = data.get(key) or []
        if isinstance(val, list):
            base[key] = [str(x) for x in val]
    return base, False, None


def write_baseline(root, baseline):
    path = os.path.join(root, BASELINE_PATH)
    parent = os.path.dirname(path)
    if parent and not os.path.isdir(parent):
        os.makedirs(parent)
    payload = dict(baseline)
    payload["version"] = BASELINE_VERSION
    payload["generated"] = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    payload["thresholds"] = {
        "maxWarnLines": MAX_WARN_LINES,
        "maxErrorLines": MAX_ERROR_LINES,
        "maxTodoPerFile": MAX_TODO_PER_FILE,
    }
    payload["oversizeFiles"] = dict(sorted(payload.get("oversizeFiles", {}).items()))
    payload["allowedCycles"] = sorted(set(payload.get("allowedCycles", [])))
    payload["allowedLayerViolations"] = sorted(set(payload.get("allowedLayerViolations", [])))
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, ensure_ascii=False, indent=2, sort_keys=False)
        fh.write("\n")
    return path


# ------------------------------------------------------------------ 主流程 --

def build_report(root, baseline, bootstrap):
    findings = []
    stats = {
        "files": 0, "packages": 0, "lines": 0,
        "oversize_warn": 0, "oversize_error": 0, "oversize_baselined": 0, "oversize_grown": 0,
        "cycles": 0, "cycles_baselined": 0,
        "layer_violations": 0, "layer_baselined": 0,
        "todo_warn": 0, "println_warn": 0,
    }

    files = collect_files(root)
    if not files:
        findings.append(Finding(
            "ERROR", SRC_DIR, 1,
            "源码目录不存在或没有 .kt 文件 (需在仓库根执行)", "scan"))
        return findings, stats

    scanned = [scan_file(rel, abs_path) for rel, abs_path in files]
    stats["files"] = len(scanned)
    stats["lines"] = sum(s["lines"] for s in scanned)

    known_packages = set([ROOT_PKG])
    for s in scanned:
        if s["package"]:
            known_packages.add(s["package"])
            # 补齐中间层包名, 便于 import 前缀解析。
            parts = s["package"].split(".")
            for cut in range(2, len(parts)):
                known_packages.add(".".join(parts[:cut]))
    stats["packages"] = len([p for p in known_packages if p == ROOT_PKG or p.startswith(ROOT_PKG + ".")])

    # ---------------------------------------------------- 1. 文件尺寸棘轮 --
    baselined_sizes = baseline.get("oversizeFiles", {}) or {}
    for s in scanned:
        rel, count = s["path"], s["lines"]
        base_lines = baselined_sizes.get(rel)
        if base_lines is not None:
            if count > base_lines:
                stats["oversize_grown"] += 1
                findings.append(Finding(
                    "ERROR", rel, 1,
                    "文件继续变胖: 基线 %d 行 -> 现在 %d 行 (+%d), 请拆分" % (
                        base_lines, count, count - base_lines),
                    "size"))
            else:
                stats["oversize_baselined"] += 1
                if count > MAX_WARN_LINES:
                    findings.append(Finding(
                        "WARN", rel, 1,
                        "已知超限文件 (基线 %d 行, 现在 %d 行) 未增长, 建议择机拆分" % (
                            base_lines, count),
                        "size"))
            continue
        if count > MAX_ERROR_LINES:
            stats["oversize_error"] += 1
            findings.append(Finding(
                "ERROR", rel, 1,
                "单文件 %d 行 > %d 行上限, 必须拆分 (可 --update-baseline 临时登记存量)"
                % (count, MAX_ERROR_LINES),
                "size"))
        elif count > MAX_WARN_LINES:
            stats["oversize_warn"] += 1
            findings.append(Finding(
                "WARN", rel, 1,
                "单文件 %d 行 > %d 行提醒线, 建议按职责拆分" % (count, MAX_WARN_LINES),
                "size"))

    # -------------------------------------------------- 包级依赖图构建 --
    graph = {p: set() for p in known_packages if p == ROOT_PKG or p.startswith(ROOT_PKG + ".")}
    edge_example = {}   # (src,dst) -> (rel, line)
    for s in scanned:
        src_pkg = s["package"]
        if not src_pkg:
            continue
        for symbol, line_no in s["imports"]:
            dst_pkg = resolve_import_package(symbol, known_packages)
            if not dst_pkg or dst_pkg == src_pkg:
                continue
            graph.setdefault(src_pkg, set()).add(dst_pkg)
            graph.setdefault(dst_pkg, set())
            edge_example.setdefault((src_pkg, dst_pkg), (s["path"], line_no))

    # ---------------------------------------------------- 2. 包依赖无环 --
    allowed_cycles = set(baseline.get("allowedCycles", []) or [])
    found_cycles = find_cycles(graph)
    stats["cycles"] = len(found_cycles)
    for cyc in sorted(found_cycles):
        if cyc in allowed_cycles:
            stats["cycles_baselined"] += 1
            continue
        first_edge = cyc.split(" -> ")[0:2]
        sample = edge_example.get((first_edge[0], first_edge[1]), (SRC_DIR, 1))
        findings.append(Finding(
            "ERROR", sample[0], sample[1],
            "包依赖成环: %s" % cyc, "cycle"))

    # -------------------------------------------------- 3. 分层依赖方向 --
    allowed_layer = set(baseline.get("allowedLayerViolations", []) or [])
    layer_hits = {}
    for (src_pkg, dst_pkg), where in edge_example.items():
        ls, ld = layer_of(src_pkg), layer_of(dst_pkg)
        if ls is None or ld is None or ls >= ld:
            continue
        key = "%s -> %s" % (src_pkg, dst_pkg)
        layer_hits.setdefault(key, (src_pkg, dst_pkg, where))
    for key in sorted(layer_hits):
        src_pkg, dst_pkg, (rel, line_no) = layer_hits[key]
        if key in allowed_layer:
            stats["layer_baselined"] += 1
            findings.append(Finding(
                "WARN", rel, line_no,
                "已知反向依赖 %s (%s -> %s), 基线豁免中, 请勿新增同类" % (
                    key, LAYER_NAMES[layer_of(src_pkg)], LAYER_NAMES[layer_of(dst_pkg)]),
                "layer"))
            continue
        stats["layer_violations"] += 1
        findings.append(Finding(
            "ERROR", rel, line_no,
            "分层倒置: %s (%s 层不得 import %s 层) %s" % (
                key, LAYER_NAMES[layer_of(src_pkg)], LAYER_NAMES[layer_of(dst_pkg)], dst_pkg),
            "layer"))

    # ------------------------------------------------------ 4. 禁止事项 --
    for s in scanned:
        rel = s["path"]
        todo_count = len(s["todo_hits"])
        if todo_count > MAX_TODO_PER_FILE:
            stats["todo_warn"] += 1
            findings.append(Finding(
                "WARN", rel, s["todo_hits"][0],
                "TODO/FIXME 标记 %d 处 > %d 处, 债务过密应拆票" % (todo_count, MAX_TODO_PER_FILE),
                "todo"))

        if rel.startswith(PRINTLN_EXCLUDE_PREFIXES):
            continue
        for line_no in s["println_hits"]:
            stats["println_warn"] += 1
            findings.append(Finding(
                "WARN", rel, line_no, "println( 应改用 AppLog", "println"))

    # --------------------------------------- 自举: 首次运行把现状登记基线 --
    if bootstrap:
        baseline["oversizeFiles"] = {
            s["path"]: s["lines"] for s in scanned if s["lines"] > MAX_ERROR_LINES
        }
        baseline["allowedCycles"] = sorted(found_cycles)
        baseline["allowedLayerViolations"] = sorted(layer_hits.keys())
        # 自举时把所有存量问题降级成 WARN, 不让历史包袱直接红掉 CI。
        downgraded = []
        for f in findings:
            if f.severity == "ERROR":
                f.severity = "WARN"
                f.message = "[自举登记] " + f.message
                downgraded.append(f)
        findings = downgraded

    return findings, stats


def find_cycles(graph):
    """DFS 枚举包级有向图中的环, 返回规范化环字符串列表。"""
    cycles = set()
    nodes = sorted(graph)

    color = {n: 0 for n in nodes}   # 0 未访问 / 1 栈上 / 2 已完成
    stack = []

    def dfs(node):
        color[node] = 1
        stack.append(node)
        for nxt in sorted(graph.get(node, ())):
            if nxt not in color:
                continue
            if color[nxt] == 1:
                idx = stack.index(nxt)
                cycles.add(canonical_cycle(stack[idx:]))
            elif color[nxt] == 0:
                dfs(nxt)
        stack.pop()
        color[node] = 2

    sys.setrecursionlimit(max(10000, len(nodes) * 20 + 1000))
    for n in nodes:
        if color[n] == 0:
            dfs(n)
    return sorted(cycles)


def print_report(findings, stats, baseline_state, baseline_path_note, bootstrap, root):
    findings = sorted(findings, key=lambda f: f.sort_key())
    lines = []
    for f in findings:
        lines.append("%s  %s:%s  %s" % (f.severity, f.path, f.line, f.message))

    errors = sum(1 for f in findings if f.severity == "ERROR")
    warns = sum(1 for f in findings if f.severity == "WARN")

    out = []
    out.append("=== 架构门禁 (arch-policy) ===")
    if bootstrap:
        out.append("NOTE  首次运行: 已按当前代码生成基线 %s, 存量问题降级为 WARN。" % baseline_path_note)
    if baseline_state:
        out.append("NOTE  %s" % baseline_state)
    out.append("")
    if lines:
        out.extend(lines)
    else:
        out.append("OK    没有发现任何架构问题")
    out.append("")
    out.append("=== 统计 ===")
    out.append("扫描: %d 个 .kt 文件 / %d 行 / %d 个本项目包" % (
        stats["files"], stats["lines"], stats["packages"]))
    out.append("尺寸棘轮: >%d 行 WARN %d 个, >%d 行 ERROR %d 个, 基线内 %d 个, 基线内继续增长 %d 个" % (
        MAX_WARN_LINES, stats["oversize_warn"], MAX_ERROR_LINES, stats["oversize_error"],
        stats["oversize_baselined"], stats["oversize_grown"]))
    out.append("依赖无环: 检出环 %d 个, 基线豁免 %d 个" % (
        stats["cycles"], stats["cycles_baselined"]))
    out.append("分层方向: 违规 %d 处, 基线豁免 %d 处" % (
        stats["layer_violations"], stats["layer_baselined"]))
    out.append("禁止事项: TODO/FIXME 过密 %d 个文件, println( %d 处" % (
        stats["todo_warn"], stats["println_warn"]))
    out.append("结论: ERROR %d / WARN %d -> %s" % (
        errors, warns, "FAIL (exit 1)" if errors else "PASS (exit 0)"))

    text = "\n".join(out)
    print(text)
    return 0 if errors == 0 else 1


def main(argv):
    root = "."
    update_baseline = False
    for arg in argv[1:]:
        if arg == "--update-baseline":
            update_baseline = True
        elif arg == "--root" or arg.startswith("--root="):
            if "=" in arg:
                root = arg.split("=", 1)[1]
            else:
                root = argv[argv.index(arg) + 1] if argv.index(arg) + 1 < len(argv) else "."
        elif arg in ("-h", "--help"):
            print(__doc__)
            return 0

    root = os.path.abspath(root)
    if not os.path.isdir(root):
        print("ERROR\t%s\t仓库根不存在" % root)
        return 1

    baseline, new_baseline, baseline_state = load_baseline(root)
    bootstrap = new_baseline

    if update_baseline:
        # 只登记当前状态, 不改代码; 登记后直接成功退出。
        baseline["oversizeFiles"] = {}
        baseline["allowedCycles"] = []
        baseline["allowedLayerViolations"] = []
        findings, stats = build_report(root, baseline, bootstrap=True)
        path = write_baseline(root, baseline)
        print("=== 架构门禁 (arch-policy) ===\n基线已更新: %s" % path)
        print("登记超限文件 %d 个, 环 %d 个, 分层例外 %d 个" % (
            len(baseline["oversizeFiles"]), len(baseline["allowedCycles"]),
            len(baseline["allowedLayerViolations"])))
        print("扫描: %d 个 .kt 文件 / %d 行 / %d 个本项目包" % (
            stats["files"], stats["lines"], stats["packages"]))
        print("(只登记现状, 未修改任何代码) -> exit 0")
        return 0

    findings, stats = build_report(root, baseline, bootstrap=bootstrap)
    if bootstrap and stats["files"] > 0:
        path = write_baseline(root, baseline)
        baseline_state = "已生成基线文件: %s" % os.path.relpath(path, root).replace(os.sep, "/")
    baseline_note = os.path.join(".", BASELINE_PATH).replace(os.sep, "/")
    return print_report(findings, stats, baseline_state, baseline_note, bootstrap, root)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
