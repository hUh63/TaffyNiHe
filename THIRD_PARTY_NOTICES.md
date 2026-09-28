# 第三方组件与许可声明（THIRD_PARTY_NOTICES）

本文件汇总 TaffyNiHe（塔菲逆核）随包分发或运行期链接的第三方组件及其许可。

> **说明**：下表为便于查阅的**汇总**，完整、权威的许可条款以各上游仓库的 `LICENSE` 文件为准。
> 标注「⚠️ 待复核」的条目尚未逐字核对上游许可原文，请以人工复核结论为准。

## 本项目许可

TaffyNiHe 采用 **GNU General Public License v3.0**（见仓库根 `LICENSE`）。

## 一、随 APK 分发的原生组件（`app/src/main/jniLibs/<abi>/`）

| 组件 | 文件 | 上游 | 许可 |
|---|---|---|---|
| rizin | `librz_native.so`（静态链接） | [rizinorg/rizin](https://github.com/rizinorg/rizin) | LGPL-3.0 |
| rz-ghidra 反编译器 | 同上 | [rizinorg/rz-ghidra](https://github.com/rizinorg/rz-ghidra) | LGPL-3.0 |
| LIEF | 同上 | [lief-project/LIEF](https://github.com/lief-project/LIEF) | Apache-2.0 |
| Capstone | `libcapstone.so` | [capstone-engine/capstone](https://github.com/capstone-engine/capstone) | BSD-3-Clause |
| Keystone | `libkeystone.so` | [keystone-engine/keystone](https://github.com/keystone-engine/keystone) | GPL-2.0 ⚠️ 待复核（与 GPL-3.0 的兼容性） |
| Unicorn（unicorn2 分支） | `libunicorn.so` | [zhkl0228/unicorn](https://github.com/zhkl0228/unicorn)（fork 自 unicorn-engine） | GPL-2.0 ⚠️ 待复核（同上） |
| blutter 引擎（多 Dart 版本） | `libblutter_<hash>.so` | [worawit/blutter](https://github.com/worawit/blutter) | MIT |
| Dart SDK 运行期代码 | 同上（静态链接进 blutter 引擎） | [dart-lang/sdk](https://github.com/dart-lang/sdk) | BSD-3-Clause |
| ICU / Capstone（blutter 侧依赖） | 同上 | 见 blutter 上游 | 见上游 |
| frida-server | `libfrida_server.so` | [frida/frida](https://github.com/frida/frida) | wxWindows Library Licence |
| cloudflared | `libcloudflared.so` | [cloudflare/cloudflared](https://github.com/cloudflare/cloudflared) | Apache-2.0 |
| demumble | `libdemumble.so` | [zhkl0228/demumble](https://github.com/zhkl0228/demumble)（fork 自 nico/demumble） | Apache-2.0（含 LLVM 部分） |
| DexKit | `libdexkit*.so` / aar | [Luckypray/DexKit](https://github.com/Luckypray/DexKit) | Apache-2.0 |
| xAnSo | `libxanso_native.so` | ⚠️ 待复核（来源与许可需补充） | ⚠️ 待复核 |
| JNA | `libjnidispatch.so` | [java-native-access/jna](https://github.com/java-native-access/jna) | LGPL-2.1 或 Apache-2.0（双许可） |
| libc++（NDK） | `libc++_shared.so` | LLVM / Android NDK | Apache-2.0 with LLVM exception |
| androidx.graphics.path | `libandroidx.graphics.path.so` | androidx | Apache-2.0 |

## 二、Java / Kotlin 依赖

### `app/libs/*.jar`（本地自带，含自建改动）

| 组件 | 文件 | 上游 | 许可 |
|---|---|---|---|
| Capstone Java 绑定 | `capstone-3.1.8-android-patched.jar`（zhkl0228 fork；Maven 最新即 3.1.8，含自研 `capstone.api.*` 层，随 unidbg JNI 契约冻结） | [zhkl0228/capstone](https://github.com/zhkl0228/capstone) | BSD-3-Clause |
| Keystone Java 绑定 | `keystone-0.9.7-android-patched.jar` | 上游 keystone Java binding | GPL-2.0 ⚠️ 待复核 |
| unidbg | `unidbg-android-0.9.9-android-patched.jar`<br>`unidbg-api-0.9.9-android-patched.jar` | [zhkl0228/unidbg](https://github.com/zhkl0228/unidbg) | Apache-2.0 |

### Gradle 依赖

| 组件 | 坐标 | 上游 | 许可 |
|---|---|---|---|
| unidbg unicorn2 后端 | `com.github.zhkl0228:unidbg-unicorn2:0.9.9` | zhkl0228/unidbg | Apache-2.0 |
| unicorn Java 绑定 | `com.github.zhkl0228:unicorn:1.0.15` | zhkl0228 | GPL-2.0 ⚠️ 待复核 |
| jadx | `io.github.skylot:jadx-core` / `jadx-dex-input:1.5.6` | [skylot/jadx](https://github.com/skylot/jadx) | Apache-2.0 |
| ARSCLib | `io.github.reandroid:ARSCLib:1.4.0` | [REAndroid/ARSCLib](https://github.com/REAndroid/ARSCLib) | Apache-2.0 |
| APKEditor | `com.github.REAndroid:APKEditor:V1.4.9` | [REAndroid/APKEditor](https://github.com/REAndroid/APKEditor) | Apache-2.0 |
| smali / baksmali / dexlib2 | `com.android.tools.smali:smali*:3.0.10` | [google/smali](https://github.com/google/smali) | BSD-3-Clause |
| DexKit | `org.luckypray:dexkit:2.3.0` | [Luckypray/DexKit](https://github.com/Luckypray/DexKit) | Apache-2.0 |
| apksig | `com.android.tools.build:apksig:9.4.1` | Android Open Source Project | Apache-2.0 |
| Bouncy Castle | `org.bouncycastle:bcpkix-jdk18on:1.86` | [bcgit/bc-java](https://github.com/bcgit/bc-java) | MIT（BC 许可） |
| apk-parser | `net.dongliu:apk-parser:2.6.10` | net.dongliu/apk-parser | Apache-2.0 |
| demumble | `com.github.zhkl0228:demumble:1.0.4` | zhkl0228/demumble | Apache-2.0 |
| Eclipse ELK | `org.eclipse.elk:*:0.12.0` | [eclipse-elk/elk](https://github.com/eclipse-elk/elk) | EPL-2.0 |
| Ktor | `io.ktor:*:3.6.0` | JetBrains | Apache-2.0 |
| OkHttp | `com.squareup.okhttp3:okhttp*:5.5.0` | Square | Apache-2.0 |
| kotlinx-serialization-json | `org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0` | JetBrains | Apache-2.0 |
| Compose / androidx | `androidx.compose.*`、`androidx.activity` | Google | Apache-2.0 |
| Shizuku | `dev.rikka.shizuku:api` / `:provider:13.1.5` | [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) | Apache-2.0 |
| Dhizuku API | `io.github.iamr0s:Dhizuku-API:2.6.0` | [iamr0s/Dhizuku](https://github.com/iamr0s/Dhizuku) | GPL-3.0 |
| JNA | `net.java.dev.jna:jna:5.19.1@aar` | java-native-access/jna | LGPL-2.1 或 Apache-2.0（双许可） |
| jsoup | `org.jsoup:jsoup:1.23.2` | jsoup | MIT |
| Fastjson | `com.alibaba:fastjson:2.0.65`（**fastjson1 兼容层**，内核为 fastjson2） | Alibaba | Apache-2.0 |
| Argon2Kt | `com.lambdapioneer.argon2kt:argon2kt:1.6.0` | lambdapioneer/argon2kt | MIT |
| commons-codec / commons-io / commons-collections4 | `commons-codec:1.21.0` / `commons-io:2.21.0` / `commons-collections4:4.5.0` | Apache Software Foundation | Apache-2.0 |
| slf4j-api | `org.slf4j:slf4j-api:2.0.20` | QOS.ch | MIT |
| markdown（rikkaHub） | `com.github.rikkahub:markdown:d79a97cc8e` | [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub) | AGPL-3.0 ⚠️ **重点待复核** |
| JUnit / org.json | 仅测试期 | — | EPL-1.0 / JSON License |

## 三、自建构建与 fork 改动留档

本项目对上游做了构建期适配与本地修改，改动留档于仓库内，便于合规审查与回归：

| 位置 | 内容 |
|---|---|
| `third_party/patches/lief-1.0.0-*.patch` | LIEF 1.0.0 的 2 处本地修复（chained union size / OAT lower_bound）—— 按版本选用 |
| `third_party/patches/lief-0.16.1-*.patch` | LIEF 0.16.1 的 3 处本地修复（chained union size / OAT lower_bound / warnings）—— 保留以支持回退 |
| `tools/blutter-matrix/` | blutter 多 Dart 版本矩阵的构建脚本与改动 |
| `tools/dex2c/` | Dex2C（dcc）构建与加固接入 |
| `tools/unidbg-unicorn-bridge/` | unidbg 的 unicorn2 JNI 桥（导出 `Java_com_github_unidbg_*` 符号） |
| `tools/PatchCapstoneClinit.java` / `PatchKeystoneClinit.java` | capstone / keystone Java 绑定的 Android 适配补丁 |
| `rizin-cross-*.ini` / `build-*.ps1` / `build-native-android.sh` | rizin + rz-ghidra 的交叉编译配置 |

## 四、需要人工复核的合规注意

以下几处建议在正式分发前确认：

1. **GPL-2.0 组件与 GPL-3.0 的组合**：Keystone、Unicorn（含 unicorn2 fork）以及它们的 Java 绑定为 GPL-2.0。需确认其是否为 "GPL-2.0-only"，若是，则与本项目 GPL-3.0 存在许可不兼容风险。
2. **AGPL-3.0 组件**：`rikkahub/markdown` 为 AGPL-3.0。AGPL 属强 copyleft 且有网络服务条款，需确认该模块的实际许可与本项目分发的兼容性；若不确定，建议替换为同类 Apache-2.0/MIT 的 Markdown 渲染库。
3. **xAnSo**：来源仓库与许可尚未记录，需补充。
4. **动态下载的组件**：`frida-server` 由 CI 在构建时从官方 release 下载注入，`cloudflared` 为官方 release 二进制，其许可随各自上游。

---

*本文件最后更新：2026-09-28。新增第三方组件时请同步更新本表。*
