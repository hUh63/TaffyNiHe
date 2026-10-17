#!/usr/bin/env python3
"""
TaffyNiHe MCP stdio <-> HTTP 桥。

用途：让只支持 stdio 的 MCP 客户端（Claude Desktop、各类桌面 MCP 宿主）用上塔菲。
桥跑在**电脑侧**，把 stdin 的 JSON-RPC 行转发到手机上的塔菲 HTTP 端点。

为什么桥在电脑侧：Android 应用没有可被外部连接的 stdin/stdout。塔菲在手机上监听 HTTP，
电脑侧用这个脚本做协议转换是最稳的形态，不需要在 APK 里塞一个额外的 native 可执行。

特性：
  * 逐行读取，一行一条 JSON-RPC（MCP stdio 规范）
  * 自动识别 SSE 响应（`event: message` + `data: {...}`），只回传 data
  * **单条响应超长保护**：超过阈值时把完整内容落盘，只回一条结构合法的精简响应，
    避免宿主因为一行几 MB 而卡死（与 App 内传输层保护同一套语义）
  * 日志一律走 stderr，stdout 只放协议数据

用法：
  python3 stdio_bridge.py --url http://127.0.0.1:8765/mcp
  python3 stdio_bridge.py --url http://192.168.1.20:8765/mcp --token <accessToken>
  python3 stdio_bridge.py --url ... --max-bytes 800000 --timeout 300

手机端：塔菲 → 设置 → 服务（MCP），确认服务已启动并记下地址与访问令牌。
若手机与电脑不同网段，可用 `adb forward tcp:8765 tcp:8765` 后连 127.0.0.1。
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

DEFAULT_MAX_BYTES = 1_200_000


def log(msg):
    sys.stderr.write("[taffy-bridge] " + str(msg) + "\n")
    sys.stderr.flush()


def spill_dir():
    base = os.environ.get("TAFFY_MCP_SPILL_DIR")
    if not base:
        base = os.path.join(os.path.expanduser("~"), ".taffy-mcp-spill")
    os.makedirs(base, exist_ok=True)
    return base


def spill(text):
    try:
        path = os.path.join(spill_dir(), "resp-%d.json" % int(time.time() * 1000))
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(text)
        return path
    except Exception as exc:  # noqa: BLE001
        log("spill failed: %s" % exc)
        return None


def shrink(raw, max_bytes):
    """把超长响应换成一条结构合法的 JSON-RPC 摘要，完整内容落盘。"""
    path = spill(raw)
    note = (
        "Response of %d bytes exceeded the %d-byte bridge cap; the full payload was written to %s. "
        "Narrow the query with limit/offset/filter, or read the spilled file directly."
        % (len(raw.encode("utf-8")), max_bytes, path or "(spill failed)")
    )
    try:
        obj = json.loads(raw)
    except Exception:  # noqa: BLE001
        obj = {}
    out = {
        "jsonrpc": "2.0",
        "id": obj.get("id"),
        "result": {"content": [{"type": "text", "text": note}]},
        "x-taffy-spilled": {"savedTo": path, "originalBytes": len(raw.encode("utf-8")), "cap": max_bytes},
    }
    return json.dumps(out, ensure_ascii=False)


def extract_sse(body):
    """SSE 响应只取 data: 行，拼起来返回；不是 SSE 就原样返回。"""
    if not body.lstrip().startswith("event:"):
        return body
    chunks = []
    for line in body.splitlines():
        if line.startswith("data:"):
            chunks.append(line[5:].strip())
    return "\n".join(chunks) if chunks else body


def post(url, token, payload, timeout):
    data = payload.encode("utf-8")
    req = urllib.request.Request(url, data=data, method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Accept", "application/json, text/event-stream")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        if resp.status == 202:
            return ""
        return resp.read().decode("utf-8", errors="replace")


def main():
    ap = argparse.ArgumentParser(description="TaffyNiHe MCP stdio<->HTTP bridge")
    ap.add_argument("--url", required=True, help="塔菲 MCP 端点，如 http://127.0.0.1:8765/mcp")
    ap.add_argument("--token", default=os.environ.get("TAFFY_MCP_TOKEN", ""), help="访问令牌（可选）")
    ap.add_argument("--max-bytes", type=int, default=DEFAULT_MAX_BYTES, help="单条响应落盘阈值（字节）")
    ap.add_argument("--timeout", type=float, default=600.0, help="单次请求超时秒数")
    args = ap.parse_args()

    log("endpoint=%s maxBytes=%d" % (args.url, args.max_bytes))
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            json.loads(line)  # 只做合法性检查，原样转发
        except Exception as exc:  # noqa: BLE001
            log("skip non-JSON input: %s" % exc)
            continue
        try:
            body = post(args.url, args.token, line, args.timeout)
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")[:400]
            log("HTTP %s: %s" % (exc.code, detail))
            body = json.dumps(
                {
                    "jsonrpc": "2.0",
                    "id": (json.loads(line).get("id") if line.startswith("{") else None),
                    "error": {"code": -32000, "message": "bridge HTTP %s: %s" % (exc.code, detail)},
                },
                ensure_ascii=False,
            )
        except Exception as exc:  # noqa: BLE001
            log("request failed: %s" % exc)
            body = json.dumps(
                {
                    "jsonrpc": "2.0",
                    "id": None,
                    "error": {"code": -32000, "message": "bridge error: %s" % exc},
                },
                ensure_ascii=False,
            )
        if not body:
            # 通知类请求：MCP stdio 规范下也可以不回
            continue
        body = extract_sse(body)
        if len(body.encode("utf-8")) > args.max_bytes:
            body = shrink(body, args.max_bytes)
        sys.stdout.write(body.replace("\n", " ").strip() + "\n")
        sys.stdout.flush()


if __name__ == "__main__":
    main()
