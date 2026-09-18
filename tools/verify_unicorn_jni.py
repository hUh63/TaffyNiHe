#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
#
# TaffyNiHe - tools/verify_unicorn_jni.py
# Copyright (C) 2026 TaffyNiHe authors
# Upstream idea: SOMCP tools/verify_unicorn_jni.py (AGPL-3.0) - reimplemented here.
#
# build-unidbg-native.sh 的构建期硬门禁：确认打包进 APK 的 libunicorn.so 是
# unidbg 的 unicorn2 **JNI 桥**，而不是裸 unicorn 引擎。
#
# 为什么必须有这个检查
# --------------------
# 塔菲通过 unidbg 的 unicorn2 后端执行模拟：Unicorn2Factory -> Unicorn2Backend ->
# com.github.unidbg.arm.backend.unicorn.Unicorn。这个 Java 类是一层纯 JNI 绑定，
# 每个入口都是 native 方法，符号名由类名+方法名推导：
#
#     Java_com_github_unidbg_arm_backend_unicorn_Unicorn_<method>
#
# 因此 APK 里的 libunicorn.so 必须导出这些符号。裸 unicorn 引擎**不会**导出它们
# （只有 uc_* 扁平 C API）；JNI 桥在 unidbg 自己的
# backend/unicorn2/src/main/native/unicorn.c 里，必须编译并链接到引擎静态库上。
#
# 只打包裸引擎是**静默失败**：System.loadLibrary("unicorn") 会成功（加载 .so 不要求
# JNI 符号可解析），于是 UnidbgEmulator 报"后端可用"，一直到 session_open 才炸
# —— BackendFactory.newBackend 吞掉绑定异常并回退 legacy UnicornBackend，随后
# NoClassDefFoundError: unicorn.Unicorn（上游 issue #91 同坑）。
#
# 用法
# ----
#     python3 tools/verify_unicorn_jni.py app/src/main/jniLibs/arm64-v8a/libunicorn.so
#     python3 tools/verify_unicorn_jni.py app/src/main/jniLibs/*/libunicorn.so
#     python3 tools/verify_unicorn_jni.py --dir app/src/main/jniLibs        # 扫 <abi>/libunicorn.so
#     python3 tools/verify_unicorn_jni.py --apk app-release.apk --abi arm64-v8a,x86_64
#
# 退出码 0 表示每个被检查的库里都找到了桥；任何缺失/不合格 → 1。
"""构建期门禁：校验 libunicorn.so 是否为 unidbg 的 unicorn2 JNI 桥。"""

from __future__ import annotations

import argparse
import os
import struct
import sys
import zipfile

JNI_PREFIX = "Java_com_github_unidbg_arm_backend_unicorn_Unicorn_"

# 后端离不开的入口（前缀匹配：带签名后缀的重载如 reg_1read__JII 也能命中，
# 上游后续新增方法也不会让门禁变脆）。JNI 把 Java 名里的 '_' 转义成 "_1"。
REQUIRED_PREFIXES = (
    "nativeInitialize",
    "nativeDestroy",
    "emu_1start",
    "emu_1stop",
    "mem_1read",
    "mem_1write",
    "mem_1map",
    "mem_1protect",
    "mem_1unmap",
    "reg_1read",
    "reg_1write",
    "context_1alloc",
    "context_1save",
    "context_1restore",
    "hook_1del",
    "registerHook",
    "registerDebugger",
    "addBreakPoint",
    "removeBreakPoint",
    "setSingleStep",
    "free",
)
# 现网桥导出 31 个入口；要求压倒性多数即可（既挡住裸引擎，也不因上游新增而误报）。
MIN_JNI_SYMBOLS = 20


class VerifyError(Exception):
    """库不可读或不满足契约。"""


def dynamic_symbols(blob: bytes) -> list[str]:
    """返回 ELF .dynsym 中**已定义**（导出）的符号名。"""
    if len(blob) < 52 or blob[:4] != b"\x7fELF":
        raise VerifyError("不是 ELF 文件")
    if blob[5] != 1:
        raise VerifyError("不支持大端 ELF")
    elf_class = blob[4]
    if elf_class == 2:  # ELF64
        shoff, = struct.unpack_from("<Q", blob, 0x28)
        shentsize, shnum, shstrndx = struct.unpack_from("<HHH", blob, 0x3A)
        sh_fmt, sym_fmt, sym_size = "<IIQQQQIIQQ", "<IBBHQQ", 24
    elif elf_class == 1:  # ELF32
        shoff, = struct.unpack_from("<I", blob, 0x20)
        shentsize, shnum, shstrndx = struct.unpack_from("<HHH", blob, 0x2E)
        sh_fmt, sym_fmt, sym_size = "<IIIIIIIIII", "<IIIBBH", 16
    else:
        raise VerifyError(f"未知 ELF class {elf_class}")
    if not shoff or not shnum or shnum > 4096:
        raise VerifyError("ELF 没有节头表（被过度 strip？）")

    sections = []
    for i in range(shnum):
        off = shoff + i * shentsize
        if off + struct.calcsize(sh_fmt) > len(blob):
            raise VerifyError("节头表截断")
        v = struct.unpack_from(sh_fmt, blob, off)
        sections.append({"name": v[0], "off": v[4], "size": v[5], "entsize": v[9]})

    def sh_name(noff: int) -> str:
        st = sections[shstrndx]
        start = st["off"] + noff
        end = blob.index(b"\0", start)
        return blob[start:end].decode("utf-8", "replace")

    named = {}
    for sec in sections:
        named.setdefault(sh_name(sec["name"]), sec)
    dynsym, dynstr = named.get(".dynsym"), named.get(".dynstr")
    if dynsym is None or dynstr is None:
        raise VerifyError("没有 .dynsym/.dynstr（没有任何导出符号）")

    strings = blob[dynstr["off"]:dynstr["off"] + dynstr["size"]]
    ent = dynsym["entsize"] or sym_size

    out: list[str] = []
    for i in range(dynsym["size"] // ent):
        off = dynsym["off"] + i * ent
        if off + struct.calcsize(sym_fmt) > len(blob):
            break
        v = struct.unpack_from(sym_fmt, blob, off)
        shndx = v[3] if elf_class == 2 else v[5]
        if shndx == 0:  # UND：导入而非导出
            continue
        noff = v[0]
        if noff >= len(strings):
            continue
        end = strings.find(b"\0", noff)
        name = strings[noff:end if end >= 0 else len(strings)].decode("utf-8", "replace")
        if name:
            out.append(name)
    return out


def check(blob: bytes, label: str, required: bool = True) -> bool:
    try:
        symbols = dynamic_symbols(blob)
    except VerifyError as exc:
        print(f"[verify-unicorn-jni] {label}")
        print(f"  FAIL: 无法解析 ELF: {exc}")
        return False

    bridge = sorted({s for s in symbols if s.startswith(JNI_PREFIX)})
    methods = {s[len(JNI_PREFIX):] for s in bridge}
    missing = [p for p in REQUIRED_PREFIXES if not any(m.startswith(p) for m in methods)]

    print(f"[verify-unicorn-jni] {label}")
    print(f"  unidbg unicorn2 JNI 导出符号: {len(bridge)}（要求 >= {MIN_JNI_SYMBOLS}）")
    if not required:
        print("  跳过（32 位 ABI 不打包 unicorn，QEMU/unicorn 需要 __uint128_t）")
        return True

    problems = []
    if len(bridge) < MIN_JNI_SYMBOLS:
        problems.append(
            "JNI 入口太少 —— 这看起来是裸 unicorn 引擎（只有 uc_* C API），"
            "而不是 unidbg 的 backend/unicorn2 JNI 桥"
        )
    if missing:
        problems.append("缺少必需入口: " + ", ".join(missing))
    if problems:
        for p in problems:
            print(f"  FAIL: {p}")
        print("  提示: 用 scripts/build-unidbg-native.sh 重新构建；桥是 unidbg"
              " backend/unicorn2/src/main/native/unicorn.c 链接引擎静态库所得，"
              "CMake 胶水见 tools/unidbg-unicorn-bridge/CMakeLists.txt")
        return False
    print("  OK: unidbg unicorn2 JNI 桥存在")
    return True


def main() -> int:
    ap = argparse.ArgumentParser(description="校验 libunicorn.so 是 unidbg unicorn2 JNI 桥")
    ap.add_argument("libraries", nargs="*", help="libunicorn.so 路径（可多个）")
    ap.add_argument("--dir", help="扫描 <dir>/<abi>/libunicorn.so")
    ap.add_argument("--apk", help="从 APK 内 lib/<abi>/libunicorn.so 校验")
    ap.add_argument("--abi", default="arm64-v8a,x86_64", help="配合 --apk 的 ABI 列表")
    ap.add_argument("--allow-missing", action="store_true", help="32 位 ABI 缺失时不视为失败")
    args = ap.parse_args()

    targets: list[tuple[str, bytes, bool]] = []
    for path in args.libraries:
        with open(path, "rb") as fh:
            targets.append((path, fh.read(), True))
    if args.dir:
        for abi in sorted(os.listdir(args.dir)):
            if abi.startswith("jniLibs-"):
                continue
            cand = os.path.join(args.dir, abi, "libunicorn.so")
            if os.path.isfile(cand):
                with open(cand, "rb") as fh:
                    targets.append((cand, fh.read(), True))
            elif not args.allow_missing:
                print(f"[verify-unicorn-jni] {cand}\n  32 位 ABI 无 unicorn 属正常，跳过")
    if args.apk:
        with zipfile.ZipFile(args.apk) as zf:
            names = set(zf.namelist())
            for abi in [a.strip() for a in args.abi.split(",") if a.strip()]:
                entry = f"lib/{abi}/libunicorn.so"
                if entry in names:
                    targets.append((f"{os.path.basename(args.apk)}!{entry}", zf.read(entry), True))
                else:
                    print(f"[verify-unicorn-jni] {args.apk}!{entry}")
                    print("  absent (skipped) —— 32 位或以 Unicorn 为可选依赖的包属正常")
    if not targets:
        print("error: 没有可校验的 libunicorn.so（给路径、--dir 或 --apk）", file=sys.stderr)
        return 2
    return 0 if all(check(blob, label) for label, blob, _ in targets) else 1


if __name__ == "__main__":
    sys.exit(main())
