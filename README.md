# 塔菲逆核 (TaffyNiHe)

> 一个运行在 Android 手机上的**聚合式逆向 MCP 网关**：开一个后台服务、对外暴露一个 MCP 地址，
> 让 AI 客户端（如玄星等支持 MCP 的应用）通过这一个地址调用手机本地的全套逆向工具——
> 反编译、脱壳、SO 分析、模拟执行、Frida、APK 回编签名、反混淆查找等。

当前版本：`1.0.1`
最低系统：Android 8.0 / API 26（arm64-v8a）

---

## ⚠️ 项目来源与开源声明（重要）

**本项目是基于 [SOMCP](https://github.com/bilieebiliee1-design/SOMCP)（作者 sjlz）二次开发的衍生作品。**

SOMCP 使用 **GPL-3.0** 许可证发布，因此本项目（塔菲逆核）同样遵循 **GPL-3.0** 开源，
完整源码在本仓库公开，任何人可自由查看、修改、再分发。

- 原项目：SOMCP —— https://github.com/bilieebiliee1-design/SOMCP
- 原作者：sjlz
- 原项目许可证：[GPL-3.0](LICENSE)

在此**特别感谢原作者 sjlz** 打下的 MCP 网关与引擎聚合基础。塔菲逆核在其之上做了 UI 重做、
新增了一批逆向工具（见下），但核心 MCP 框架与部分引擎集成源自 SOMCP，版权与署名归原作者所有。

> 若原作者对本二开有任何异议，请联系我，我会积极配合处理。

---

## 二次开发新增/改动

在 SOMCP 基础上，塔菲逆核主要做了：

- **UI 彻底重做**：命令中枢式首页（中央星核启停 + 卫星环绕功能），脱离原版底部导航布局。
- **反编译**：jadx（DEX→Java）、baksmali（DEX→Smali）、apk_decode（清单/资源/权限）。
- **脱壳**：dex_unpack（root 内存 dump，绕过磁盘加固壳）。
- **反混淆查找**：dex_search（基于 DexKit，靠字符串/特征反查被混淆的类/方法）。
- **动态**：frida_control（内置 frida-server 起停、进程枚举）。
- **回编签名**：smali_assemble、apk_rebuild（APKEditor 完整回编/合并拆分包）、apk_sign（v1/v2/v3）。
- **情报收集**：string_scan（URL/密钥/JWT/AK-SK 扫描）、apk_sign_info（签名证书）、apk_diff（两版对比）、apk_extract（资源提取）。
- SO/Native 层（rizin/LIEF/unidbg 等）沿用 SOMCP 已集成的引擎。

---

## 第三方组件与许可

本项目集成的第三方库/引擎各自遵循其上游许可证，GPL-3.0 声明不替代它们的原有条款：

| 组件 | 用途 | 许可证 |
|------|------|--------|
| SOMCP | MCP 网关框架 / 引擎聚合基础 | GPL-3.0 |
| rizin | 反汇编 / 分析 | LGPL-3.0 |
| unidbg | 模拟执行 | Apache-2.0 |
| jadx | DEX 反编译 | Apache-2.0 |
| APKEditor / ARSCLib | APK 解包回编 | Apache-2.0 |
| smali/baksmali | DEX↔Smali | BSD |
| DexKit | dex 反混淆查找 | LGPL-3.0 / Apache-2.0 |
| frida | 动态插桩 | wxWindows / LGPL |
| apksig | APK 签名 | Apache-2.0 |

---

## 构建

```bash
# 需要 JDK 21 + Android SDK (NDK 28.2, compileSdk 36)
./gradlew :app:assembleDebug
```

产物在 `app/build/outputs/apk/`。

---

## 许可证

本项目依据 [GNU General Public License v3.0](LICENSE) 发布（继承自 SOMCP）。
你可以自由使用、修改、再分发本项目，但衍生作品必须同样以 GPL-3.0 开源并保留署名。

**仅供安全研究与学习交流，请勿用于任何非法用途。分析对象请限于你拥有或已获授权的目标。**
