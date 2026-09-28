# 原生可执行通道（execve + jniLibs）

## 为什么需要它

Android 10 起，应用私有目录（`filesDir` / `cacheDir` / `codeCacheDir`）因 SELinux + W^X
**不允许 `execve`**：即便你自己写进去一个可执行文件，也会被内核拒绝。

例外是 **`nativeLibraryDir`** —— APK 安装时解压出的原生库目录仍具可执行权限。
于是可以把自己的 Android bionic PIE 可执行文件**命名成 `lib<name>.so`**，放进
`jniLibs/<abi>/`，安装后就被解压到 `nativeLibraryDir/lib<name>.so`，可以直接执行：

- 不需要 root、不需要 Shizuku；
- 不需要 proot / chroot、不需要 Linux rootfs；
- 不需要把二进制下载到可写目录再执行（那条路恰恰是被禁止的）。

塔菲自己的 `libcloudflared.so`（隧道）与 `libfrida_server.so` 一直是这么跑的；
`core/NativeExec.kt` 把这条已经验证过的通道通用化。

前置条件（本项目已满足）：

| 条件 | 本项目设置 |
|---|---|
| 原生库解压到磁盘 | `AndroidManifest.xml` → `android:extractNativeLibs="true"` |
| 保留原始权限位 | `build.gradle.kts` → `packaging { jniLibs { useLegacyPackaging = true } }` |
| ABI 与文件匹配 | 文件放 `jniLibs/arm64-v8a/` 等对应目录 |

## 架构

```text
设置页「本地可执行」            MCP 工具 taffy_native_exec
        \                          /
         \                        /
          v                      v
        core/NativeExec.kt  ← 统一实现
         |-- resolve()   名称白名单 lib[A-Za-z0-9_]+.so + canonical path 必须落在 nativeLibraryDir
         |-- elfOf()     解析 ELF 头 + program header（e_type / e_machine / bits / PT_INTERP）
         |-- run()       一次性执行：argv 直传（无 shell）、stdin、超时强杀、stdout/stderr 有界收集
         |-- startDaemon() 常驻后台：输出重定向到 cacheDir 日志，返回 pid
         `-- processes() / kill() / tailLog()  受管进程与日志
```

执行形态判定（用于列表展示，不作为硬门槛）：

| `e_type` | PT_INTERP | 展示 | 说明 |
|---|---|---|---|
| `ET_EXEC` (2) | 任意 | `exec` | 经典可执行（Go 静态二进制常见） |
| `ET_DYN` (3) | 有 | `pie` | 动态 PIE 可执行 |
| `ET_DYN` (3) | 无 | `pie-or-lib` | 静态 PIE 可执行**或**共享库，需结合权限位判断 |
| 其他 | — | `lib` | 共享库 |

「可运行」的判定是 `是 ELF && ABI 匹配 && (有可执行位 || 是 exec/pie 形态)`，两者取或 ——
避免因权限位或形态启发式误判而挡住真正能跑的文件；真正能否 `execve` 由内核裁决，
失败时工具会返回结构化的 `EXIT_NONZERO` / `EXEC_TIMEOUT` / `EXECUTABLE_NOT_FOUND`。

## 怎么加一个新的可执行文件

1. **交叉编译**成 Android bionic 目标（与目标 ABI 一致）的 PIE 可执行文件。
   静态链接最省事（不依赖 `libc++_shared.so` 之外的任何东西）；动态链接需一并打包依赖。
2. **改名**为 `lib<name>.so`（只允许字母、数字、下划线，最长 61 字符），放进
   `app/src/main/jniLibs/<abi>/`。命名必须符合 `^lib[A-Za-z0-9_]{1,60}\.so$`，
   否则通道会拒绝执行。
3. **构建脚本**：`app/src/main/cpp/CMakeLists.txt` 加 `add_subdirectory(<module>)`，
   或在 CI（`.github/workflows/build-multiabi.yml`）里下载/编译后 `cp` 到 `jniLibs/<abi>/`。
   `libcloudflared.so` 就是 CI 里下载官方 release 二进制改名的。
4. 装到设备后，`设置 → 本地可执行` 会立即列出它并给出 ELF 判定。

## 安全边界

- **只允许 `nativeLibraryDir`**：文件名白名单 + `canonicalPath` 必须确实落在该目录内，
  拒绝 `..`、符号链接逃逸与绝对路径。
- **参数不经 shell**：以 argv 数组直接 `execve`，天然免疫命令注入；另有参数条数（≤64）
  与单参数长度（≤4096）上限。
- **资源上限**：超时最大 900 秒（到时 `destroyForcibly`）；stdout / stderr 各自有界收集，
  超出即截断并标记 `truncated`。
- **不执行用户提供的任意二进制**：通道只跑随 APK 签名分发、安装时解压的那一份。
- **后台进程可管理**：`ps` 列出受管进程（pid / 命令 / uptime / 存活），`kill` 先 `destroy()`
  再 `destroyForcibly()`，`log` 有界读取日志尾部。

## 与其它执行通道的关系

| 通道 | 权限要求 | 适用 |
|---|---|---|
| `core/NativeExec.kt`（本通道） | 无 | 随 APK 打包的单文件 CLI（cloudflared、frida-server、自建工具） |
| Linux rootfs（proot / chroot） | 无 root 走 proot，有 root 走 chroot | 需要完整 Linux 用户态、apk/apt 包管理 |
| `RootShell` | root / Shizuku | 系统级操作（`pm install`、`/data` 读写、进程注入） |

三者互补：能用本通道解决的单文件工具，就不要引入 proot 或 root 依赖。

## 已知限制

- 只支持 ABI 与设备匹配的可执行文件；`armeabi-v7a` 设备上跑 `arm64-v8a` 的二进制会失败。
- 通道**不**支持从私有目录执行 —— 这是 Android 的限制，不是本项目的取舍。
- 大型二进制（如 53MB 的 `libfrida_server.so`）仍在 APK 内，会增加安装包体积；
  切换 ABI 或新增可执行文件都会增大包体，发布前请留意体积预算。
