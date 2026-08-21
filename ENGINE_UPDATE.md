# 引擎更新说明（rizin/lief → 上游 v1.0.17 全静态 librz_native.so）

## 结论（已核实，2026-08）

上游 SOMCP v1.0.17 的 `librz_native.so`（63.6MB）为 **全静态链接**（仅依赖系统 libc/libm/libz/liblog/libdl/libc++_shared），
内含 **rizin 0.10.0**（我们当前 0.8.0）+ LIEF 0.16.1 + ghidra/sleigh 反编译器（254 个 ghidra 符号），
JNI 符号与我们的 Kotlin 引擎 **完全兼容**：

- `Java_com_soreverse_mcp_engine_LiefEngine_*`（9 个）— 与我们一致
- `Java_com_soreverse_mcp_nativecore_RizinNativeEngine_*`（14 个）— 与我们一致（我们缺 rzSelfTest，新增无害）
- 上游多 `SignatureVerifier_*`（2 个，原生签名校验）— 我们不调用，不冲突

## 替换步骤

1. 下载上游 APK（约 49MB）：
   https://github.com/bilieebiliee1-design/SOMCP/releases/download/v1.0.17/SOMCP-main-arm64-v8a.apk
2. 解出 `lib/arm64-v8a/librz_native.so`（63.6MB）
3. 替换 `app/src/main/jniLibs/arm64-v8a/librz_native.so`
4. **删除**以下 26 个文件（已被静态链接替代）：
   - `librz_arch.so` `librz_bin.so` `librz_bp.so` `librz_config.so` `librz_cons.so` `librz_core.so`
     `librz_crypto.so` `librz_debug.so` `librz_demangler.so` `librz_diff.so` `librz_egg.so`
     `librz_flag.so` `librz_hash.so` `librz_il.so` `librz_io.so` `librz_lang.so` `librz_magic.so`
     `librz_main.so` `librz_reg.so` `librz_search.so` `librz_sign.so` `librz_socket.so`
     `librz_syscall.so` `librz_type.so` `librz_util.so`
   - `libcore_ghidra.so`
5. **保留**（独立依赖，勿删）：
   `libcapstone.so` `libkeystone.so` `libunicorn.so` `libjnidispatch.so` `libdisassembler.so`
   `libdemumble.so`（Unidbg 模拟路径硬依赖）
   `libc++_shared.so`（新 so 的运行时依赖）
   `libblutter*` `libcloudflared.so` `libfrida_server.so` `libxanso_native.so` `libdsmcp_native.so` `libandroidx.graphics.path.so`

## 体积影响

| | 当前 | 替换后 |
|---|---|---|
| librz_native.so | 3.6MB | 63.6MB |
| librz_*.so × 25 | ~28MB | 删除 |
| libcore_ghidra.so | 9.9MB | 删除 |
| **合计** | ~41.5MB | 63.6MB（净增 ~22MB） |

## 运行时验证要点

- 升级后首次打开请验证：disasm / rz 命令 / ghidra 反编译（rzDecompile）/ LIEF 解析 / xref
- 上游 Kotlin 侧可能解析了与 0.10.0 匹配的 JSON 输出格式；若个别命令输出字段变化，
  我们的 Kotlin 解析层（EngineRuntimeRead 等）可能需微调 —— 以设备实测为准
- rizin_core.cpp 源码仍引用旧 API（rz_io_read_at），但 CMake 已禁用、走预编译 so，不影响运行；
  如需重编译需同步升级 cpp 源码（rz_io_read_at → rz_io_read_at_mapped，rz_diff_bytes_new 去掉第 5 参）
