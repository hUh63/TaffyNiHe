# 16 KiB page size 兼容（Android 15+）

Android 15 起有 16 KiB page size 的设备（部分 Pixel、新骁龙平台）。这类设备上，
bionic 把 APK 里的 native 库**按 16 KiB 粒度**映射，并要求：

1. 每个 `PT_LOAD` 的 `p_align >= 16384`（不满足时 `dlopen` 直接失败）；
2. `p_offset % p_align == p_vaddr % p_align`；
3. 理想情况下两个 `PT_LOAD` 不要落在同一个 16 KiB 页里（一页只能有一种保护属性）。

APK 侧还有一条：未压缩的 `lib/*.so` 条目起始偏移必须 16 KiB 对齐。这一条已由
`ZipRepack` 保证（`NATIVE_LIB_ALIGN = 16384`，等价 `zipalign -P 16`）。

## 仓库现状（arm64-v8a，实测）

| .so | p_align | 结论 |
| --- | --- | --- |
| `libcloudflared.so` | 0x10000 | 达标 |
| `libjnidispatch.so` | 0x10000 | 达标 |
| `librz_native.so` / `libc++_shared.so` / `libcapstone.so` / `libkeystone.so` / `libunicorn.so` / `libdemumble.so` | 0x4000 | 达标（NDK r30 默认） |
| `libxanso_native.so` / `libdisassembler.so` / `libblutter_bridge.so` / `libblutter_*.so` / `libfrida_server.so` | 0x4000 | 达标 |
| `libandroidx.graphics.path.so` / `libproot*.so` / `libandroid-shmem.so` / `libpty_native.so` / `libtalloc.so` | 0x4000 | 达标 |
| **`libdsmcp_native.so`** | **0x1000** | **不达标** |
| **`libtermux.so`** | **0x1000** | **不达标** |

两个不达标的库都**没有本仓库源码**（`libdsmcp_native.so` 来自上游 SOMCP 的预编译产物，
`libtermux.so` 来自 `termux-app` 0.118.0 的 AAR），无法用 NDK 重新链接，
因此记入 `tools/elf-align-baseline.json`：构建放行但打警告。

## 影响面

- `libdsmcp_native.so`：`BinaryEngine` 的 `System.loadLibrary` 失败 → `loaded = false`，
  .NET / PE 相关工具（`dotnet_detect` / `list_types` / `dump_il` / `edit_il` / `pe_patch_*` 等）
  全部返回 "not loaded"。**不会崩，但整套工具不可用。**
- `libtermux.so`：`com.termux.terminal.JNI` 的静态初始化块抛 `UnsatisfiedLinkError`，
  类初始化失败 → 终端（pty fork/exec）不可用。

## 为什么不"手工改 p_align"

看起来只要把 `p_align` 从 0x1000 改成 0x4000 就行，但**段会共用 16 KiB 页**：
老链接器按 4 KiB 排段（例如 R 段 `[0, 0x22b0)`、R+X 段从 `0x3000` 开始），
两段在 16 KiB 页 `[0, 0x4000)` 里重叠。此时一页只能有一个保护属性，
后映射的段会覆盖前一个，Android 上的表现是加载失败或段属性错乱。

验证方法（工具已入库）：

```bash
python3 tools/check-elf-align.py --dir app/src/main/jniLibs/arm64-v8a
```

它除了检查 `p_align`，还会统计"被两个以上 `PT_LOAD` 共同覆盖的 16 KiB 页"，
后者就是必须**重新链接**而不能改头部的判据。

## 正确的修复路径

1. **拿到源码，用 NDK r28+ 重新编译**（NDK 默认 `-Wl,-z,max-page-size=16384`）。
   - `libtermux.so`：源码是 `termux-app` 的 `terminal-emulator/src/main/jni/termux.c`，
     需要新建 `termux-terminal-emulator/src/main/cpp/`，用 CMake 构建，
     并把 `termux-native.properties` 的 sha256 与符号校验改成校验构建产物。
   - `libdsmcp_native.so`：需要上游 SOMCP 的 C++ 源码。
2. 修好后从 `tools/elf-align-baseline.json` 删掉对应条目 —— 基线只允许收缩。
3. 若暂时无法重建，至少在对应功能入口给出可读提示，而不是让
   `UnsatisfiedLinkError` 冒到 UI。
