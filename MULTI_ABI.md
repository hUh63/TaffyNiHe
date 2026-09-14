# 多 ABI 支持说明（arm64-v8a / armeabi-v7a / x86 / x86_64）

塔菲逆核自 v1.3.0 起支持四个 Android ABI，每个 ABI 独立出包。

## 构建方式

由 `.github/workflows/build-multiabi.yml` 负责，两阶段：

1. **prep（4 ABI 矩阵）** 为每个 ABI 备齐 native so：
   | so | 来源 |
   |---|---|
   | `librz_native.so` | 自建：meson 交叉编译 rizin + cmake 交叉编译 LIEF → 链接 |
   | `libcapstone.so` / `libkeystone.so` | 自建（`scripts/build-unidbg-native.sh`，源码 clone） |
   | `libunicorn.so` | 自建（**仅 64 位**：QEMU 依赖 `__uint128_t`） |
   | `libxanso_native.so` | 自建（third_party/xAnSo-src，ELF section 重建） |
   | `libcloudflared.so` | 官方 release 预编译二进制（cloudflared-linux-*） |
   | `libfrida_server.so` | 官方 frida-server release（按 ABI 下载） |
   | `libc++_shared.so` | 取自 NDK sysroot |
   | `libjnidispatch.so` | JNA AAR 自动带入 |

2. **package** 下载各 ABI artifact → 归位 `jniLibs/<abi>/` → `./gradlew assembleRelease -PabiFilter=<4 ABI>`。

## 引擎版本跟踪

`build-multiabi.yml` 暴露 `rizin_ref` 派发参数（默认 `v0.9.1`）。
上游 rizin `main` 存在编译错误（`librz/util/file.c` 的 `n_bytes` undeclared）时保持稳定 tag；
上游修复后，把 `rizin_ref` 改为 `main` 即可跟踪最新引擎（无需改代码）。

## ABI splits 参数化

`app/build.gradle.kts` 的 ABI splits 默认只出 `arm64-v8a`（保持既有单 ABI 发布流程不变），
可用 `-PabiFilter=arm64-v8a,armeabi-v7a,x86,x86_64` 覆盖。

## 未覆盖库与原因（溯源结论）

以下两个库目前只有 arm64 产物。**这不是遗漏，而是经过溯源后的取舍**，非 arm64 缺失时对应能力优雅降级，不影响 App 启动与核心引擎。

### `libblutter_*.so`（Flutter/Dart 分析 runner）

- 现状：`jniLibs/arm64-v8a/` 下有 4 个 runner（Dart 3.11.5 / 3.12.2 / 3.13.0 / 3.13.1），**每个约 43 MB**。
- 构建链：`.github/workflows/build-blutter-runner.yml`，其中 NDK/ICU/capstone 全部**硬编码 `arm64-v8a`**
  （`-DANDROID_ABI=arm64-v8a`、`--icu-root build/blutter-matrix/arm64-v8a/icu`）。
  每个 runner 还必须针对**特定 Flutter engine revision** 编出对应 hash 的 `.so`（不可跨版本复用）。
- 为何不做多 ABI：体量为 `4 ABI × 4 runner × 43 MB ≈ 700 MB`（未压缩），且 blutter 的运行前提是
  从目标 APK 提取 libflutter/libapp 后按 revision 匹配 runner——在 arm64 设备以外并无实际需求。
  → 保持 arm64-only，非 arm64 降级。

### `libdsmcp_native.so`（网络/协议侧 native 扩展）

- 现状：仓库内只有预编译的 `arm64-v8a` 二进制（约 712 KB），**源码既不在本仓库也不在上游 SOMCP**。
- 为何不做多 ABI：**无源码即无法交叉编译**，也没有官方 release 可下载。
  → 只能维持 arm64-only，非 arm64 降级（相关能力不可用）。
- 后续若拿到源码，可复用 `build-unidbg-native.sh` 的 ABI 参数模式接入 multiabi 流水线。

## 后续可做的事

- 上游 rizin 修复 `librz/util/file.c` 编译错误后，把 `rizin_ref` 切成 `main` 跟踪最新引擎。
- 若上游/社区提供 `libdsmcp_native` 源码，比照 `libxanso_native` 的接入方式补多 ABI。
