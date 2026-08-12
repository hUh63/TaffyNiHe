#!/usr/bin/env python3
"""基于远程最新 tree 推送本地提交 39303ae 的 9 个变更文件。"""
import base64
import json
import subprocess
import sys
import urllib.request

TOKEN = subprocess.check_output(
    ["git", "config", "--get", "remote.origin.url"], cwd="/sandbox/workspace/TaffyNiHe", text=True
).strip()
TOKEN = TOKEN.split("ghp_")[1].split("@")[0]
TOKEN = "ghp_" + TOKEN if not TOKEN.startswith("ghp_") else TOKEN

REPO = "hUh63/TaffyNiHe"
API = "https://api.github.com/repos/" + REPO
COMMIT = "39303ae"          # 本地提交
REMOTE_COMMIT = "af26c18262ab8c49bafbe28d168253dc8131001c"  # 远程最新
REMOTE_TREE = "8d2659dfb058e3ff76c76ba8a1dfd0043a10c600"    # 远程最新 tree

FILES = [
    "app/src/main/java/com/soreverse/mcp/LogcatViewerPage.kt",
    "app/src/main/java/com/soreverse/mcp/SettingsHub.kt",
    "app/src/main/java/com/soreverse/mcp/UiModels.kt",
    "app/src/main/java/com/soreverse/mcp/core/PermissionManager.kt",
    "app/src/main/java/com/soreverse/mcp/mcp/ToolCatalog.kt",
    "app/src/main/aidl/com/soreverse/mcp/core/IShizukuService.aidl",
    "app/src/main/java/com/soreverse/mcp/CapturePage.kt",
    "app/src/main/java/com/soreverse/mcp/core/ShizukuUserService.kt",
    "app/src/main/java/com/soreverse/mcp/mcp/CaptureTools.kt",
]


def api(method, url, payload=None):
    req = urllib.request.Request(url, method=method)
    req.add_header("Authorization", "Bearer " + TOKEN)
    req.add_header("Accept", "application/vnd.github+json")
    data = None
    if payload is not None:
        data = json.dumps(payload).encode()
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, data=data) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        body = e.read().decode()
        print(f"HTTP {e.code} {url}\n{body[:500]}", file=sys.stderr)
        sys.exit(1)


# 1. 上传 blobs
tree_items = []
for path in FILES:
    content = subprocess.check_output(
        ["git", "show", f"{COMMIT}:{path}"], cwd="/sandbox/workspace/TaffyNiHe"
    )
    blob = api("POST", f"{API}/git/blobs", {"content": base64.b64encode(content).decode(), "encoding": "base64"})
    tree_items.append({"path": path, "mode": "100644", "type": "blob", "sha": blob["sha"]})
    print(f"blob {path} -> {blob['sha'][:8]}")

# 2. 创建 tree（基于远程最新 tree）
tree = api("POST", f"{API}/git/trees", {"base_tree": REMOTE_TREE, "tree": tree_items})
print(f"tree -> {tree['sha']}")

# 3. 创建 commit
commit = api("POST", f"{API}/git/commits", {
    "message": "feat: 修复 Logcat 无日志（Shizuku UserService 通道）+ LogFox 样式补齐 + 抓包工具\n\n"
               "- 根因: Shizuku 13.1.5 已移除 Shizuku.newProcess，反射必然失败导致特权通道全挂，\n"
               "  静默降级普通进程后 Android 8.0+ 无权限读 logcat → 空输出\n"
               "- 修复: 照 LogFox 方案实现 bindUserService + 自定义 UserService（shell uid 执行 logcat），\n"
               "  PermissionManager.exec/startPrivilegedStream 的 Shizuku 通道全部走 UserService\n"
               "- Logcat 查看器: 启动自检通道 + 无权限引导提示条（打开 Shizuku/授权）\n"
               "- LogFox 样式: 崩溃记录卡片化（JAVA/NATIVE/ANR 分类、包名/时间/行数、复制/删除、黑底终端样式）、\n"
               "  过滤器增强（包名/PID/正则/区分大小写/仅崩溃ANR）、录制列表增强（时间/大小/路径/分享/删除）\n"
               "- 新增抓包: taffy_capture MCP 工具 + CapturePage 图形界面（环境检测/快捷采集/tcpdump 抓包）\n"
               "- Settings 入口: MCP 工具区加抓包卡片",
    "tree": tree["sha"],
    "parents": [REMOTE_COMMIT],
})
print(f"commit -> {commit['sha']}")

# 4. 更新 ref
ref = api("PATCH", f"{API}/git/refs/heads/main", {"sha": commit["sha"], "force": False})
print(f"ref -> {ref['object']['sha'][:12]}")
print("DONE")
