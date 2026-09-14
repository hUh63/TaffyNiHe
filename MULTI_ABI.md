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

## 已知限制（可优雅降级）

以下库目前仅有 arm64 版构建产物，非 arm64 ABI 暂不打包，缺失时对应能力降级、不影响 App 启动与核心引擎：

- `libdsmcp_native.so`（源码不在本仓库）
- `libblutter_*.so`（Flutter 分析，构建链较重）
