# libtermux.so 的源码与重建方式

`src/main/jniLibs/arm64-v8a/libtermux.so` 是终端 pty 的 JNI 层。

## 来源

`termux.c` 逐字取自 [termux/termux-app](https://github.com/termux/termux-app) `v0.118.0`
的 `terminal-emulator/src/main/jni/termux.c`（GPL-3.0，与本项目一致）。
本仓库此前放的是从 `terminal-emulator-0.118.0.aar` 里抠出来的预编译 `.so`。

## 为什么要自己编

那个预编译 `.so` 的 `PT_LOAD` `p_align` 只有 `0x1000`。Android 15 起有 16 KiB
page size 的设备，bionic 要求 `p_align >= 16384`，否则直接拒绝加载 ——
表现是终端（pty fork/exec）整块功能静默不可用，不崩也不报错。

PATCH 只能重新链接：老链接器按 4 KiB 排段，两个 `PT_LOAD` 会共享同一个 16 KiB 页，
而一页只能有一种保护属性。改头部字段没用。

## 怎么重建

手动触发 `.github/workflows/build-termux-native.yml`，它会用 NDK 编译并校验：

- 每个 `PT_LOAD` 的 `p_align >= 16384`；
- `p_offset % p_align == p_vaddr % p_align`；
- 5 个 JNI 符号齐全（`createSubprocess` / `setPtyWindowSize` / `setPtyUTF8Mode` / `waitFor` / `close`）。

校验通过后把 artifact 里的 `libtermux.so` 覆盖到
`src/main/jniLibs/<abi>/libtermux.so` 提交即可。`tools/check-elf-align.py`
会持续守住这条线：换回不达标的二进制会直接阻塞构建。
