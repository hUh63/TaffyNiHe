#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
#
# SOMCP - build-unidbg-native.sh
# Copyright (C) 2026 SOMCP authors
# Upstream: https://github.com/bilieebiliee1-design/SOMCP
#
# This program is free software: you can redistribute it and/or modify it
# under the terms of the GNU General Public License version 3 as published
# by the Free Software Foundation.
#
# This program is distributed in the hope that it will be useful, but
# WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
# or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
# for more details.
#
# You should have received a copy of the GNU General Public License along
# with this program. If not, see <https://www.gnu.org/licenses/>.
#
# build-unidbg-native.sh - Cross-compile the Android native libraries required
# by the Unidbg emulation backend using the Android NDK.
#
# Linux/macOS counterpart of build-unidbg-native.ps1.
#
# Background: unidbg 0.9.9 Maven artifacts only ship Linux natives (the
# libcapstone.so / libkeystone.so / libunicorn.so inside the jars are
# linux_64 / linux_aarch64 only), and there is no reliable Android prebuilt
# download, so the APK does not bundle them by default and emulate_* returns
# EMULATOR_UNAVAILABLE. This script builds the shared libs from the in-repo
# submodules (third_party/*) for a given ABI and copies them into
# app/src/main/jniLibs/<ABI>/; rebuild the APK afterwards to enable Unidbg.
#
# Usage:
#   ./build-unidbg-native.sh
#   ./build-unidbg-native.sh -Abi armeabi-v7a
#   ./build-unidbg-native.sh -Ndk /opt/android-ndk/29.0.14206865 -SkipKeystone
#
# Options:
#   -Abi <abi>        Android ABI to build (default: arm64-v8a)
#                     [arm64-v8a|armeabi-v7a|x86|x86_64]
#   -Ndk <path>       Android NDK root (default: $ANDROID_HOME / $ANDROID_SDK_ROOT
#                     or common install locations; expects NDK 29.0.14206865)
#   -CMake <path>     cmake binary (default: SDK cmake 3.22.1 if found, else PATH)
#   -SkipCapstone     skip building capstone
#   -SkipKeystone     skip building keystone
#   -SkipUnicorn      skip building unicorn
#   -h, --help        show this help
#
# Notes:
#   - unicorn 段产出的 libunicorn.so 是「unicorn 引擎(静态) + unidbg backend/unicorn2
#     JNI 桥」链接成的单一库；只打包裸引擎会导致后端看似可用、session_open 必失败
#     （上游 issue #91）。构建期由 tools/verify_unicorn_jni.py 门禁。
#     需要 third_party/unidbg-src（zhkl0228/unidbg v0.9.9）提供桥源码。
#   - libjnidispatch.so is provided automatically by the JNA AAR
#     (net.java.dev.jna:jna); no need to build it.
#   - libdisassembler.so / libdemumble.so only serve optional diagnostic
#     paths in unidbg 0.9.9 and have no Android prebuilt source;
#     UnidbgEmulator loads them tolerantly (warning only), so they are not
#     built here either.
#   - The CMake flags target NDK 29 / CMake 3.22 / unidbg 0.9.9; if you bump
#     the NDK or CMake, adjust the flags per the upstream READMEs.
set -euo pipefail

ABI="${ABI:-arm64-v8a}"
NDK="${NDK:-}"
CMAKE_BIN="${CMAKE_BIN:-}"
SKIP_CAPSTONE=0
SKIP_KEYSTONE=0
SKIP_UNICORN=0

usage() {
  cat <<'EOF'
Usage: build-unidbg-native.sh [options]

Cross-compile capstone/keystone/unicorn for Android and copy the .so into
app/src/main/jniLibs/<ABI>/ (enables the Unidbg emulation backend in the APK).
Linux/macOS counterpart of build-unidbg-native.ps1.

Options:
  -Abi <abi>          Android ABI to build (default: arm64-v8a)
                      [arm64-v8a|armeabi-v7a|x86|x86_64]
  -Ndk <path>         Android NDK root (default: ANDROID_HOME/ANDROID_SDK_ROOT
                      or common install locations; expects NDK 29.0.14206865)
  -CMake <path>       cmake binary (default: SDK cmake 3.22.1 if found, else PATH)
  -SkipCapstone       skip building capstone
  -SkipKeystone       skip building keystone
  -SkipUnicorn        skip building unicorn
  -h, --help          show this help

Prerequisites:
  - git submodule update --init --recursive
  - Android NDK 29 + ninja (Linux: ninja-build, macOS: brew install ninja)
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -Abi|--abi) ABI="$2"; shift 2 ;;
    -Ndk|--ndk) NDK="$2"; shift 2 ;;
    -CMake|--cmake) CMAKE_BIN="$2"; shift 2 ;;
    -SkipCapstone|--skip-capstone) SKIP_CAPSTONE=1; shift ;;
    -SkipKeystone|--skip-keystone) SKIP_KEYSTONE=1; shift ;;
    -SkipUnicorn|--skip-unicorn) SKIP_UNICORN=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "error: unknown argument: $1" >&2; usage >&2; exit 1 ;;
  esac
done

# Whitelist the ABI so the value is never interpolated into filesystem or
# cmake arguments from untrusted input (path-traversal guard).
VALID_ABIS=("arm64-v8a" "armeabi-v7a" "x86" "x86_64")
abi_ok=0
for a in "${VALID_ABIS[@]}"; do
  if [[ "$a" == "$ABI" ]]; then abi_ok=1; break; fi
done
if [[ $abi_ok -eq 0 ]]; then
  echo "error: unsupported ABI '$ABI' - must be one of: ${VALID_ABIS[*]}" >&2
  exit 1
fi

# Script lives at the repo root, so the script dir IS the project root.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# 塔菲: 本脚本位于 <repo>/scripts/ 下(上游 SOMCP 在仓库根), 自动上溯到仓库根,
# 使 third_party/ 与 app/src/main/jniLibs/ 的相对路径仍然正确。
if [ -f "$SCRIPT_DIR/settings.gradle.kts" ]; then PROJECT="$SCRIPT_DIR"; else PROJECT="$(cd "$SCRIPT_DIR/.." && pwd)"; fi
JNI_LIBS="$PROJECT/app/src/main/jniLibs/$ABI"
mkdir -p "$JNI_LIBS"

# --- Locate the Android NDK -------------------------------------------------
if [[ -z "$NDK" ]]; then
  for cand in \
    "$ANDROID_HOME/ndk/29.0.14206865" \
    "$ANDROID_SDK_ROOT/ndk/29.0.14206865" \
    "$HOME/Library/Android/sdk/ndk/29.0.14206865" \
    "$HOME/Android/Sdk/ndk/29.0.14206865" \
    /opt/android-sdk/ndk/29.0.14206865 \
    /opt/android-ndk/29.0.14206865
  do
    if [[ -f "$cand/build/cmake/android.toolchain.cmake" ]]; then
      NDK="$cand"
      break
    fi
  done
fi
if [[ -z "$NDK" || ! -f "$NDK/build/cmake/android.toolchain.cmake" ]]; then
  echo "error: NDK toolchain not found. Set -Ndk or ANDROID_HOME/ANDROID_SDK_ROOT (expects NDK 29.0.14206865)." >&2
  exit 1
fi
TOOLCHAIN="$NDK/build/cmake/android.toolchain.cmake"
echo "[unidbg-native] NDK: $NDK"

# --- Locate cmake (prefer the SDK's 3.22.1, mirroring the PS1 default) -------
NINJA_BIN="$(command -v ninja || true)"
if [[ -z "$CMAKE_BIN" ]]; then
  for base in "$ANDROID_HOME" "$ANDROID_SDK_ROOT" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    [[ -z "$base" || ! -d "$base" ]] && continue
    for v in 3.22.1.5040 3.22.1.5000 3.22.1.4700 3.22.1.4501 3.22.1; do
      if [[ -x "$base/cmake/$v/bin/cmake" ]]; then
        CMAKE_BIN="$base/cmake/$v/bin/cmake"
        NINJA_BIN="$base/cmake/$v/bin/ninja"
        break 2
      fi
    done
    if [[ -z "$CMAKE_BIN" && -x "$base/cmake/bin/cmake" ]]; then
      CMAKE_BIN="$base/cmake/bin/cmake"
    fi
  done
fi
CMAKE_BIN="${CMAKE_BIN:-cmake}"
if ! command -v "$CMAKE_BIN" >/dev/null 2>&1 && [[ ! -x "$CMAKE_BIN" ]]; then
  echo "error: cmake not found. Set -CMake or install cmake >= 3.22." >&2
  exit 1
fi
if [[ -z "$NINJA_BIN" ]]; then
  NINJA_BIN="$(command -v ninja || true)"
fi
if [[ -z "$NINJA_BIN" || ! -x "$NINJA_BIN" ]]; then
  echo "error: ninja not found - install it (Linux: ninja-build, macOS: brew install ninja) or use the ninja shipped with the Android SDK CMake package." >&2
  exit 1
fi
# Make sure the Ninja generator can find ninja even when it only lives inside
# the SDK's cmake directory.
NINJA_DIR="$(dirname "$NINJA_BIN")"
export PATH="$NINJA_DIR:$PATH"
echo "[unidbg-native] cmake: $CMAKE_BIN (ninja: $NINJA_BIN)"

BUILD_ROOT="$PROJECT/third_party/unidbg-native-build/$ABI"

build_one() {
  local name="$1" src="$2"
  shift 2
  if [[ ! -d "$src" ]]; then
    echo "error: $name source missing: $src - run 'git submodule update --init --recursive' first" >&2
    exit 1
  fi
  local build="$BUILD_ROOT/$name"
  rm -rf "$build"
  mkdir -p "$build"
  echo "[unidbg-native] configuring $name ..."
  "$CMAKE_BIN" -S "$src" -B "$build" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM=android-26 \
    -DCMAKE_BUILD_TYPE=Release \
    "$@"
  echo "[unidbg-native] building $name ..."
  "$CMAKE_BIN" --build "$build" --parallel 4
}

if [[ $SKIP_CAPSTONE -eq 0 ]]; then
  # Prefer the zhkl0228 fork (the unidbg 0.9.9 JNA bindings were written
  # against its API); fall back to the official capstone-4.0.2-src
  # (cs_open/cs_disasm ABI is stable and compatible).
  cap="$PROJECT/third_party/zhkl-capstone-src"
  [[ -d "$cap" ]] || cap="$PROJECT/third_party/capstone-4.0.2-src"
  build_one capstone "$cap" \
    -DCAPSTONE_BUILD_STATIC=OFF -DCAPSTONE_BUILD_SHARED=ON \
    -DCAPSTONE_BUILD_TESTS=OFF -DCAPSTONE_BUILD_CSTOOL=OFF \
    -DCAPSTONE_ARCHITECTURE_DEFAULT=OFF \
    -DCAPSTONE_ARM_SUPPORT=ON -DCAPSTONE_ARM64_SUPPORT=ON
  RZ_SO="$(find "$BUILD_ROOT/capstone" -name 'libcapstone.so' -print -quit)"
  [ -n "$RZ_SO" ] || { echo "error: libcapstone.so not produced"; exit 1; }
  cp "$RZ_SO" "$JNI_LIBS/"
  echo "[unidbg-native] copied libcapstone.so -> $JNI_LIBS"
fi

if [[ $SKIP_KEYSTONE -eq 0 ]]; then
  # keystone 默认只产静态库 libkeystone.so 需要 BUILD_SHARED_LIBS=ON
  # (否则 CMake 输出 llvm/lib/libkeystone.a, unidbg 的 JNA 绑定加载不到 .so)。
  build_one keystone "$PROJECT/third_party/keystone-engine-src" \
    -DBUILD_LIBS_ONLY=ON -DLLVM_BUILD_TOOLS=OFF -DBUILD_SHARED_LIBS=ON \
    -DLLVM_TARGETS_TO_BUILD="AArch64;ARM;X86" 
  RZ_SO="$(find "$BUILD_ROOT/keystone" -name 'libkeystone.so' -print -quit)"
  [ -n "$RZ_SO" ] || { echo "error: libkeystone.so not produced"; exit 1; }
  cp "$RZ_SO" "$JNI_LIBS/"
  echo "[unidbg-native] copied libkeystone.so -> $JNI_LIBS"
fi

if [[ $SKIP_UNICORN -eq 0 ]]; then
  # ── v1.3.10 修复（上游 issue #91 同坑）─────────────────────────────────────────
  # 旧实现有两个致命缺陷：
  #   1) -DUNICORN_ARCH=arm,aarch64 —— cmake 里换行符/逗号都不是列表分隔符，
  #      UNICORN_ARCH 只认空格/分号，这个值会被当成**单一** arch 名 → arm/aarch64
  #      后端根本没编进去；
  #   2) 更关键：它只把裸引擎产物当 libunicorn.so 打包，从不编译链接 unidbg 的
  #      JNI 桥（backend/unicorn2/src/main/native/{unicorn.c,sample_arm.c,sample_arm64.c}）。
  #      裸引擎能被 System.loadLibrary 成功加载（加载 .so 不要求 JNI 符号可解析），
  #      于是后端一直显示"可用"，直到 session_open 才炸：Unicorn2Factory 绑定失败，
  #      BackendFactory 吞掉异常回退 legacy UnicornBackend → NoClassDefFoundError。
  # 现在：unicorn 按**静态库**构建（libunicorn.a），再与 JNI 桥链接成唯一产物
  # libunicorn.so（CMake 胶水见 tools/unidbg-unicorn-bridge/CMakeLists.txt），
  # 最后用 tools/verify_unicorn_jni.py 做构建期硬门禁。
  uni="$PROJECT/third_party/unicorn-zhkl0228"
  [[ -d "$uni" ]] || uni="$PROJECT/third_party/unicorn-engine-unicorn2"
  if [[ ! -d "$uni" ]]; then
    echo "error: unicorn source missing: $uni - run 'git submodule update --init --recursive' first" >&2
    exit 1
  fi
  # fail-fast：必须用 zhkl0228/unicorn 的 unicorn2 分支。master 与 unicorn-engine 官方仓库
  # 都已移除 uc_ctl_set_cpu_model / uc_ctl_remove_cache，用它桥会编译不过（白等十几分钟）。
  if ! grep -q 'uc_ctl_set_cpu_model' "$uni/include/unicorn/unicorn.h" 2>/dev/null; then
    echo "error: $uni 不是 zhkl0228/unicorn 的 unicorn2 分支（缺 uc_ctl_set_cpu_model）" >&2
    echo "       请: git clone --depth 1 --branch unicorn2 https://github.com/zhkl0228/unicorn.git third_party/unicorn-zhkl0228" >&2
    exit 1
  fi

  BRIDGE_SRC="$PROJECT/third_party/unidbg-src/backend/unicorn2/src/main/native"
  if [[ ! -f "$BRIDGE_SRC/unicorn.c" ]]; then
    echo "error: unidbg unicorn2 JNI 桥源码缺失: $BRIDGE_SRC/unicorn.c" >&2
    echo "       桥不在 unicorn 仓库里，而在 zhkl0228/unidbg 仓库。请执行：" >&2
    echo "         git clone --depth 1 --branch v0.9.9 https://github.com/zhkl0228/unidbg.git third_party/unidbg-src" >&2
    echo "       （CI 由 .github/workflows/build-multiabi.yml 的克隆步骤提供）" >&2
    exit 1
  fi

  # 1) 组桥：我们的 CMake 胶水 + 上游桥源码（只取需要编译的文件）
  BRIDGE_SRC_DIR="$BUILD_ROOT/unicorn-bridge-src"
  rm -rf "$BRIDGE_SRC_DIR"; mkdir -p "$BRIDGE_SRC_DIR"
  cp "$PROJECT/tools/unidbg-unicorn-bridge/CMakeLists.txt" "$BRIDGE_SRC_DIR/"
  for f in unicorn.c sample_arm.c sample_arm64.c unicorn.h khash.h \
           com_github_unidbg_arm_backend_unicorn_Unicorn.h; do
    [[ -f "$BRIDGE_SRC/$f" ]] || { echo "error: missing bridge file: $BRIDGE_SRC/$f" >&2; exit 1; }
    cp "$BRIDGE_SRC/$f" "$BRIDGE_SRC_DIR/"
  done

  # 2) 一次 cmake 搞定：引擎静态库 + JNI 桥 -> libunicorn.so
  build_one unicorn-jni "$BRIDGE_SRC_DIR" -DUNICORN_SRC="$uni"
  RZ_SO="$(find "$BUILD_ROOT/unicorn-jni" -name 'libunicorn.so' -print -quit)"
  [ -n "$RZ_SO" ] || { echo "error: libunicorn.so not produced (unidbg unicorn2 JNI bridge)"; exit 1; }
  cp "$RZ_SO" "$JNI_LIBS/libunicorn.so"

  # 3) 瘦身：保留 .dynsym/.dynstr（--strip-unneeded 会保留动态符号表，JNI 绑定才有效）
  HOST_TAG="$(ls -d "$NDK/toolchains/llvm/prebuilt"/* 2>/dev/null | head -1)"
  STRIP_BIN="${HOST_TAG:-/nonexistent}/bin/llvm-strip"
  if [[ -x "$STRIP_BIN" ]]; then
    "$STRIP_BIN" --strip-unneeded "$JNI_LIBS/libunicorn.so" || true
  fi

  # 4) 构建期门禁：必须是 JNI 桥（裸引擎在这里就暴露，而不是等用户 session_open 才炸）
  if command -v python3 >/dev/null 2>&1 && [[ -f "$PROJECT/tools/verify_unicorn_jni.py" ]]; then
    python3 "$PROJECT/tools/verify_unicorn_jni.py" "$JNI_LIBS/libunicorn.so"
  else
    echo "[unidbg-native] warning: 未找到 python3/tools/verify_unicorn_jni.py，跳过 JNI 桥门禁" >&2
  fi

  ls -lh "$JNI_LIBS/libunicorn.so"
  echo "[unidbg-native] built libunicorn.so (unicorn engine + unidbg unicorn2 JNI bridge) -> $JNI_LIBS"
fi

echo "[unidbg-native] DONE - rebuild the APK to enable the Unidbg backend (libjnidispatch.so is provided automatically by the JNA AAR)"
