#!/usr/bin/env bash
# build-rz-ghidra-android.sh — 交叉编译 rz-ghidra 反编译器插件（libcore_ghidra.so）。
#
# 背景：塔菲逆核的 rizin_core.cpp 里 applyGhidraConfig() 从应用自己的 nativeLibraryDir
# 加载 <dir>/libcore_ghidra.so，并设置 SLEIGHHOME（sleigh 规格已随仓库放在
# app/src/main/assets/rizin/plugins/rz_ghidra_sleigh）。此前 APK 里没有这个 .so，
# 运行时日志只有 "ghidra plugin missing at .../lib/<abi>/libcore_ghidra.so"，
# 伪 C 的 pdg(rz-ghidra) 后端永远不可用（只能回退 Exbin 反编译器）。
#
# 本脚本为四个 Android ABI 各自构建：
#   1) 一份 **SHARED** Rizin SDK（rz-ghidra 的 CMake 走 find_package(Rizin)，
#      需要 lib/cmake/Rizin/RizinConfig.cmake；而 build-native-android.sh 只产静态归档）
#   2) rz-ghidra 的 core 插件 → 以固定名 libcore_ghidra.so 放进 app/src/main/jniLibs/<abi>/
#   3) 插件链接到的共享 librz_*.so 一并放进 jniLibs（否则运行时 dlopen 失败）
#
# 依赖：NDK（ANDROID_NDK_HOME 或 ANDROID_HOME/ndk）、third_party/rizin-src（工作流已 clone）、
#      meson / ninja / cmake / git。脚本内会自行 clone third_party/rz-ghidra。
set -euo pipefail

# --- NDK 定位 ---
NDK_ROOT="${ANDROID_NDK_HOME:-}"
if [ -z "$NDK_ROOT" ] && [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/ndk" ]; then
    NDK_ROOT="$(ls -1d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1)"
fi
if [ -z "$NDK_ROOT" ] || [ ! -d "$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin" ]; then
    echo "[rz-ghidra] ERROR: Android NDK linux-x86_64 toolchain not found (ANDROID_NDK_HOME=$ANDROID_NDK_HOME, ANDROID_HOME=${ANDROID_HOME:-})." >&2
    exit 1
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

RIZIN_SRC="third_party/rizin-src"
RZ_GHIDRA_SRC="third_party/rz-ghidra"
if [ ! -d "$RIZIN_SRC/librz/include" ]; then
    echo "[rz-ghidra] ERROR: Rizin source not found at '$RIZIN_SRC'. Ensure it is cloned before this step." >&2
    exit 1
fi

# rizin 的 pf_parser.c 用了 float ldexpf()（2025-08 引入）；bionic 在 API<30 不导出它，
# 共享库链接（-Wl,--no-undefined）会在 android-26 上失败。换成等价的 double ldexp 形式。
# sed 幂等：替换后 'ldexpf(' 模式即消失，重复执行无副作用。
sed -i 's/float val = ldexpf((float)man, -24);/float val = (float)ldexp((double)man, -24);/' \
    "$RIZIN_SRC/librz/type/pf/pf_parser.c" || true

# 每个 ABI 一份 cross 模板（仓库内已有，下面把 Windows 相关 token 改写成 Linux）。
ABIS=(arm64-v8a armeabi-v7a x86 x86_64)
TPL=(rizin-cross-aarch64.ini rizin-cross-armv7a.ini rizin-cross-i686.ini rizin-cross-x86_64.ini)

for i in "${!ABIS[@]}"; do
    abi="${ABIS[$i]}"
    tpl="${TPL[$i]}"
    echo "[rz-ghidra] ======================================== ABI: $abi"

    # --- 1) SHARED Rizin SDK 安装（find_package(Rizin) 需要它）---
    rz_sdk="rizin-sdk/$abi"
    if [ -f "$rz_sdk/lib/cmake/Rizin/RizinConfig.cmake" ]; then
        echo "[rz-ghidra] Rizin SDK for $abi already installed; reusing."
    else
        build_dir="rizin-build/ghidra-$abi"
        rm -rf "$build_dir"
        mkdir -p "$build_dir"
        NDK_ESC="$(printf '%s' "$NDK_ROOT" | sed 's/[&|\\]/\\&/g')"
        sed -e "s|@NDK_ROOT@|$NDK_ESC|g" \
            -e "s|windows-x86_64|linux-x86_64|g" \
            -e "s|\.cmd||g" -e "s|\.exe||g" \
            "$tpl" > "$build_dir/cross.ini"
        cat > "$build_dir/native.ini" <<'EOF'
[binaries]
c = 'cc'
cpp = 'c++'
ar = 'ar'
ld = 'ld'
pkg-config = 'false'
[built-in options]
c_args = ['-O2']
cpp_args = ['-O2', '-std=c++17']
c_std = 'c11'
cpp_std = 'c++17'
EOF
        meson setup "$build_dir" "$RIZIN_SRC" \
            --cross-file "$build_dir/cross.ini" \
            --native-file "$build_dir/native.ini" \
            --prefix "$(pwd)/$rz_sdk" \
            --default-library shared -Dblob=true -Dstatic_runtime=false \
            -Duse_sys_capstone=disabled -Ddebugger=false -Dsubprojects_check=false
        meson compile -C "$build_dir"
        meson install -C "$build_dir"
    fi

    # --- 2) rz-ghidra 源码 + submodule（反编译器 C++ 在 submodule 里）---
    if [ ! -e "$RZ_GHIDRA_SRC/.git" ]; then
        git clone --depth 1 https://github.com/rizinorg/rz-ghidra.git "$RZ_GHIDRA_SRC"
    fi
    git -C "$RZ_GHIDRA_SRC" submodule update --init --recursive --depth 1 || true

    # --- 3) 构建并安装 rz-ghidra 插件 ---
    rz_ghidra_build="third_party/rz-ghidra-build-$abi"
    rz_ghidra_install="third_party/rz-ghidra-install-$abi"
    rm -rf "$rz_ghidra_build"
    # android-26 与 Rizin SDK 的 cross 模板、以及 app 的 minSdk 26 对齐；
    # armeabi-v7a 上 bionic API<24 隐藏 fseeko/ftello，libc++ 的 <fstream> 会编译失败。
    cmake -S "$RZ_GHIDRA_SRC" -B "$rz_ghidra_build" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$NDK_ROOT/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" \
        -DANDROID_PLATFORM=android-26 \
        -DCMAKE_PREFIX_PATH="$(pwd)/$rz_sdk" \
        -DCMAKE_FIND_ROOT_PATH="$(pwd)/$rz_sdk" \
        -DCMAKE_INSTALL_PREFIX="$(pwd)/$rz_ghidra_install" \
        -DBUILD_CUTTER_PLUGIN=OFF \
        -DBUILD_SLEIGH_PLUGIN=OFF \
        -DBUILD_SLASPECS=OFF \
        -DUSE_SYSTEM_ZLIB=OFF
    # BUILD_SLASPECS 默认 ON，会用刚编出的（Android 目标）sleighc 去编译所有 .slaspec，
    # 而 sleighc 无法在 x86_64 runner 上运行 → 必须 OFF。运行期要用的 .sla/.cspec/.ldefs
    # 已随仓库放在 app/src/main/assets/rizin/plugins/rz_ghidra_sleigh。
    cmake --build "$rz_ghidra_build" --config Release -j "$(nproc)"
    cmake --install "$rz_ghidra_build"

    # --- 4) 定位 core 插件并复制为 libcore_ghidra.so ---
    jni_dir="app/src/main/jniLibs/$abi"
    mkdir -p "$jni_dir"
    core_so="$(find "$rz_ghidra_install" -type f \( \
        -name 'libcore_ghidra.so' -o -name 'librz_core_ghidra.so' -o -name 'librz_ghidra-core.so' \) | head -1)"
    if [ -z "$core_so" ]; then
        core_so="$(find "$rz_ghidra_install" -type f -name '*ghidra*.so*' | head -1)"
    fi
    if [ -z "$core_so" ]; then
        echo "::error::[rz-ghidra] core plugin .so not found under $rz_ghidra_install after build"
        exit 1
    fi
    cp -L "$core_so" "$jni_dir/libcore_ghidra.so"
    echo "[rz-ghidra] copied $(basename "$core_so") -> $jni_dir/libcore_ghidra.so"

    # --- 5) 复制插件依赖的共享 librz_*.so ---
    found_rz=0
    for f in "$rz_sdk"/lib/librz_*.so*; do
        [ -e "$f" ] || continue
        cp -L "$f" "$jni_dir/"
        found_rz=1
    done
    if [ "$found_rz" -eq 0 ]; then
        echo "::error::[rz-ghidra] no shared librz_*.so found under $rz_sdk/lib"
        exit 1
    fi

    # --- 6) 自检 ---
    test -f "$jni_dir/libcore_ghidra.so" || {
        echo "::error::[rz-ghidra] libcore_ghidra.so missing from $jni_dir after install"
        exit 1
    }
    echo "[rz-ghidra] $abi: libcore_ghidra.so + shared Rizin libs staged in $jni_dir"
done

echo "[rz-ghidra] Done. libcore_ghidra.so is staged for all four ABIs under app/src/main/jniLibs/."
