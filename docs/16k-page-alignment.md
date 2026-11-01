# 16 KiB page size 兼容（Android 15+）

Android 15 起有 16 KiB page size 的设备（部分 Pixel、新骁龙平台）。这类设备上，
bionic 把 APK 里的 native 库**按 16 KiB 粒度**映射，并要求：

1. 每个 `PT_LOAD` 的 `p_align >= 16384`（不满足时 `dlopen` 直接失败）；
2. `p_offset % p_align == p_vaddr % p_align`；
3. 两个 `PT_LOAD` 不能落在同一个 16 KiB 页里（一页只能有一种保护属性）。

APK 侧还有一条：未压缩的 `lib/*.so` 条目起始偏移必须 16 KiB 对齐。这一条已由
`ZipRepack` 保证（`NATIVE_LIB_ALIGN = 16384`，等价 `zipalign -P 16`）。

## 仓库现状（arm64-v8a，实测）

| .so | p_align | 结论 |
| --- | --- | --- |
| `libcloudflared.so` / `libjnidispatch.so` | 0x10000 | 达标 |
| `librz_native.so` / `libc++_shared.so` / `libcapstone.so` / `libkeystone.so` / `libunicorn.so` / `libdemumble.so` | 0x4000 | 达标（NDK r30 默认） |
| `libxanso_native.so` / `libdisassembler.so` / `libblutter_bridge.so` / `libblutter_*.so` / `libfrida_server.so` | 0x4000 | 达标 |
| `libandroidx.graphics.path.so` / `libproot*.so` / `libandroid-shmem.so` / `libpty_native.so` / `libtalloc.so` | 0x4000 | 达标 |
| `libtermux.so` | 0x4000 | 达标（v1.5.29 用 NDK 从 `cpp/termux.c` 重建） |
| `libdsmcp_native.so` | 0x4000 | 达标（v1.5.31 用 NDK 从 vendored 源码 `cpp/dsmcp/` 重建，4 个 ABI 全齐） |

## libtermux.so 是怎么修的

它原来是从 `terminal-emulator-0.118.0.aar` 里抠出来的预编译产物。源码其实一直是公开的
（`termux-app` 的 `terminal-emulator/src/main/jni/termux.c`，GPL-3.0，与本项目一致），
现在收在 `termux-terminal-emulator/src/main/cpp/termux.c`。

重建后的差别不只是 `p_align`：

```
旧（0x1000）                       新（0x4000）
PT_LOAD off=0x0    vaddr=0x0        PT_LOAD off=0x0    vaddr=0x0
PT_LOAD off=0x1cd0 vaddr=0x1cd0     PT_LOAD off=0x1950 vaddr=0x5950
  └ 两段挤在同一个 4 KiB 页            └ 落在一页之外，权限互不覆盖
```

重建方式：手动触发 `.github/workflows/build-termux-native.yml`（NDK 编译 + 校验
`p_align`、同余关系、5 个 JNI 符号），把 artifact 里的 `libtermux.so` 覆盖到
`src/main/jniLibs/arm64-v8a/` 并同步 `termux-native.properties` 的 `binary.sha256`。
`build-apk.yml` 因此仍不需要安装 NDK。

## libdsmcp_native.so 是怎么修的

那份二进制在 SOMCP 上游确实没有源码（上游 `cpp/CMakeLists.txt` 只构建
`rz_native` / `blutter_bridge` / `xanso_native` 三个 target）。但顺着 33 个 JNI 导出符号
反查，源码在 **`zigou2162/DSMCP`**（GPL-3.0，与本项目同许可），整份 vendored 进
`app/src/main/cpp/dsmcp/`，用 `.github/workflows/build-dsmcp-native.yml` 重编。

除了 `p_align`，原来那份二进制还带着三个会让 .NET 结果**静默出错**的 bug
（`parse_metadata_root` 偏移、表号枚举整组错位 4、IL 标签格式串），都在 vendored
源码里一并修掉了，细节见 `THIRD_PARTY_NOTICES.md` 与 `RELEASE_NOTES.md` 的 v1.5.31 段。

顺带解决的两件事：

- 原来只有 `arm64-v8a` 有这份库，其它三个 ABI 上 .NET/PE 工具一直是不可用的；现在四个 ABI 都有。
- .NET 的读写能力已用纯 Kotlin 重做（`com.dsmcp.tool.engine.DotNetReader`），
  native 只负责 ELF/PE 的 parse 与 patch —— 即使某个 ABI 上 `.so` 加载失败，.NET 工具仍能用。

## 为什么不"手工改 p_align"

老链接器按 4 KiB 排段，两个 `PT_LOAD` 会落在同一个 16 KiB 页里。只把 `p_align` 从
0x1000 改成 0x4000 会破坏"一页一种保护属性"，加载失败或段属性错乱。真正要改的是
段的 `vaddr` 间距，那需要同步修正 `.rela.dyn` 的 `r_offset`、`.dynamic` 的各个 `d_ptr`、
`init_array`/`fini_array` 的内容 —— 等于自己写一个链接器。

验证方法（工具已入库）：

```bash
python3 tools/check-elf-align.py --dir app/src/main/jniLibs/arm64-v8a
```

它除了检查 `p_align`，还会统计"被两个以上 `PT_LOAD` 共同覆盖的 16 KiB 页"，
后者就是必须重新链接而不能改头部的判据。存量不达标记录在
`tools/elf-align-baseline.json`，**只允许收缩**。
