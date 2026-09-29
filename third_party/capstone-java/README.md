# capstone-java（逆核自建绑定）

TaffyNiHe 内置的 capstone Java 绑定源码。上游 `zhkl0228/capstone` 停更在 **3.1.8（capstone 4.0 API）**，
本目录把它重做为**单一 JNI 实现**，引擎跟进官方 **capstone 5.0.9**。

## 为什么 fork

- 上游绑定走 **JNA**：`capstone.Capstone` 静态块用 `Native.extractFromResourcePath` 从 jar 资源里解出
  native 库（不能按 ABI 分发），构造函数还有 `coreVersion == (CS_API_MAJOR<<8|CS_API_MINOR)` 的硬校验。
- unidbg 0.9.9 对 capstone 的依赖面**只有两处**：
  1. `capstone.api.*` 抽象层 —— `Disassembler` / `Instruction` / `DisassemblerFactory` / `OpShift` /
     `RegsAccess` + `arm,arm64` 的 `Operand`/`OpInfo`/`OpValue`/`MemType`；
  2. `capstone.Capstone$OpInfo`（marker 接口）。
  unidbg **不调用** `Instruction.getId()`，也**不引用** `Arm_const`/`Arm64_const`
  —— 因此 capstone 4→5 的指令/寄存器枚举移位对 unidbg 无影响。
- 结论：只要保住 `capstone.api.*` 与 `Capstone$OpInfo` 的签名，就能在 **零改 unidbg** 的前提下把引擎换到 5.x。

## 结构

```
java/capstone/            # 绑定源码 → 构建 app/libs/capstone-5.0.9-android-patched.jar
  Capstone.java           # 转发器：委托 capstone.jni.FastDisassembler；保留 OpInfo + CS_* 常量
  api/**                  # unidbg 消费的抽象层（与上游一致，未改动）
  jni/**                  # FastDisassembler / FastInstruction 等 JNI 侧实现
native/                   # JNI 胶水 → 构建 libdisassembler.so（取自上游，未改动）
  disassembler.c/h
  reg_mapping.c/h         # capstone <-> unicorn 寄存器映射
  capstone_jni_FastDisassembler.h
```

相对上游 `bindings/java/` 的改动只有三处：

1. `Capstone.java` 重写为转发器（不再持有 JNA 结构体、不再做版本校验）；
2. 删除全部 JNA 结构体类与 `Arm_const`/`Arm64_const`/`X86_const` 等常量类；
3. `jni/FastDisassembler.java` 去掉 `org.scijava.nativelib` 依赖与加载 jar 内 native 的静态块
   （`libdisassembler.so` 由 Android 随 APK 的 `jniLibs` 提供，`System.loadLibrary` 即可）。

## 构建

由 `scripts/build-unidbg-native.sh` 统一构建（CI 的 `.github/workflows/build-multiabi.yml` 在
「编译 unidbg 原生库」步骤调用它，逐 ABI 产出）：

1. 官方 `capstone-engine/capstone@5.0.9` 克隆到 `third_party/capstone-src` → 交叉编译出 `libcapstone.so`；
2. 编译 `native/*.c` → `libdisassembler.so`（`DT_NEEDED: libcapstone.so`）。

两个关键适配：

- **CMake 选项改名**：5.x 用标准名 `BUILD_SHARED_LIBS` / `BUILD_STATIC_LIBS`
  （4.x 的 `CAPSTONE_BUILD_SHARED` / `CAPSTONE_BUILD_STATIC` 已作废）。
- **去掉 SOVERSION**：capstone 5.x 会给 shared 库设 `SOVERSION 5` → SONAME `libcapstone.so.5`，
  而 Android 只打包 `libcapstone.so`，`libdisassembler.so` 的 `DT_NEEDED` 就会解析不到。
  脚本删掉该 `set_target_properties(capstone_shared PROPERTIES VERSION … SOVERSION …)` 块，
  让 SONAME 保持 `libcapstone.so`。

## 许可

- C 引擎与 `native/` 胶水：[capstone-engine/capstone](https://github.com/capstone-engine/capstone)（BSD-3-Clause）。
- `java/`：衍生自 [zhkl0228/capstone](https://github.com/zhkl0228/capstone)（BSD-3-Clause）。
