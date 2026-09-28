# TaffyNiHe v1.3.59

本版为**依赖与工具链大版本升级**：功能与 v1.3.58 一致（分析页工具聚合、Flutter/Dart AOT 分析视图、原生可执行通道等见 v1.3.58 说明），重点是把整套技术栈推到当前最新可用版本。

## 🔧 工具链

- **Android Gradle Plugin 9.2.1 → 9.4.1**，**Kotlin 2.4.0 → 2.4.20**，**Gradle 9.4.1 → 9.8.0**。
- **compileSdk 36 → 37（minor 2）**：compose 1.12.x 起要求 `compileSdk ≥ 37`（平台以 `platforms;android-37.2` 形式提供）；`targetSdk` 仍保持 36，不影响运行时行为。
- **NDK 28.2.13676358 → 30.0.16248370**：4 个 ABI 的原生库（rizin+LIEF、unidbg/capstone/keystone/unicorn、xAnSo）全部重新构建通过。

## ⬆️ 依赖升级

**逆向工具链**

- **LIEF 0.16.1 → 1.0.0**：`LIEF::to_json` 在 1.0 拆分为 `LIEF::ELF::to_json`，代码用 `LIEF_VERSION_MAJOR` 宏兼容两代；本地补丁集同步换代（新增 `lief-1.0.0-oat-lower_bound`、`lief-1.0.0-chained-union-size`）。
- **jadx 1.5.1 → 1.5.6**（DEX→Java 反编译质量提升）
- **smali / baksmali / dexlib2 3.0.9 → 3.0.10**
- **ARSCLib 1.3.5 → 1.4.0**
- **DexKit 2.0.4 → 2.3.0**
- **apksig 8.7.3 → 9.4.1**（与 AGP 同版本线）

**框架与基础库**

- compose-bom 2026.06.01 → **2026.09.00**
- ktor 3.5.1 → **3.6.0**、okhttp 5.4.0 → **5.5.0**
- ELK 0.9.1 → **0.12.0**（并补 `org.eclipse.xtext.xbase.lib` —— ELK 0.12 的算法元数据 provider 需要 Xtend runtime，否则 R8 报 Missing class）
- jsoup 1.22.2 → **1.23.2**、slf4j-api 2.0.16 → **2.0.20**
- bcpkix-jdk18on 1.78.1 → **1.86**、jna 5.10.0 → **5.19.1**
- commons-io **2.22.0**、commons-codec **1.22.1**、commons-collections4 **4.6.0**
- Dhizuku-API 2.5.3 → **2.6.0**

**内置二进制**

- cloudflared 2026.9.1 → **2026.9.3**
- frida-server 17.18.0 → **17.19.0**

## 🧊 冻结清单（明确「不升」，并已登记原因）

`fastjson 1.2.83`、`capstone 3.1.8`、`keystone 0.9.7`、`unicorn 1.0.15`、`commons-{codec,collections4,io}`、`demumble`、`apk-parser` 的版本由 **unidbg 0.9.9 的 pom 锁定** —— 单独升级会破坏反射调用（fastjson 的类名）或 JNI 契约。已在 `build.gradle.kts` 加注释、在 `DEPENDENCIES.md` 建立冻结登记，防止后人误升。

## 🛠 工程

- CI 原生构建缓存 key 现在包含 **LIEF 版本 + NDK 版本**（此前不含版本号，会让升级静默复用旧产物，「看起来成功其实没生效」）。
- LIEF 补丁改为**按版本选择 + 应用失败只告警**：上游修好后旧补丁失配不再把流水线弄红。
- 补齐 `packaging.resources` 规则：bouncycastle 1.86 的 `META-INF/BCRSA204.*`、`META-INF/LICENSE.md`，以及 smali 3.0.10 / apksig 9.4.1 引入的裸 `LICENSE` 跨 jar 重名。
- `DEPENDENCIES.md` 新增「版本锁定与冻结清单」「工具链版本」「升级作业检查清单」三节。

---

**完整对比**：https://github.com/hUh63/TaffyNiHe/compare/v1.3.58...v1.3.59
