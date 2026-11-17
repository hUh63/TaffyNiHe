package com.soreverse.mcp.core

import android.content.Context
import java.io.File

/**
 * 环境自检（借鉴太墟 `taixu-host doctor`）：一次性回答「这台设备上哪些能力现在可用」。
 *
 * 用户最容易卡住的地方不是「功能在哪个菜单」，而是「为什么这个功能没反应」——
 * 没 root、Shizuku 没授权、Python 没装、内置引擎没随包安装、存储满了。
 * 这些都能用便宜的方式探测出来，没必要让人自己猜。
 *
 * 全部只读，不申请权限、不改配置。任何一项探测抛异常都按「不可用 + 原因」返回，
 * 不让自检本身变成新的失败点。
 */
object EnvDoctor {

    data class Item(
        val key: String,
        val titleZh: String,
        val titleEn: String,
        val ok: Boolean,
        val detailZh: String,
        val detailEn: String,
        val hintZh: String = "",
        val hintEn: String = "",
    )

    fun check(context: Context, zh: Boolean): List<Item> = listOf(
        root(zh),
        shizuku(zh),
        python(context, zh),
        nativeEngine(context, zh),
        storage(context, zh),
    )

    private fun root(zh: Boolean): Item {
        val r = runCatching { RootShell.isRootAvailable() }
        val ok = r.getOrDefault(false)
        return Item(
            key = "root",
            titleZh = "root 通道",
            titleEn = "root access",
            ok = ok,
            detailZh = if (ok) "su 可用（uid=0）" else (r.exceptionOrNull()?.message ?: "su 不可用"),
            detailEn = if (ok) "su available (uid=0)" else (r.exceptionOrNull()?.message ?: "su unavailable"),
            hintZh = if (ok) "" else "动态分析、frida-server、/data 读写需要 root 或 Shizuku",
            hintEn = if (ok) "" else "Dynamic analysis, frida-server and /data access need root or Shizuku",
        )
    }

    private fun shizuku(zh: Boolean): Item {
        val ok = runCatching { PermissionManager.isShizukuGranted() }.getOrDefault(false)
        return Item(
            key = "shizuku",
            titleZh = "Shizuku",
            titleEn = "Shizuku",
            ok = ok,
            detailZh = if (ok) "已授权，可免 root 提权" else "未授权或未安装",
            detailEn = if (ok) "Granted, rootless privilege available" else "Not granted or not installed",
            hintZh = if (ok) "" else "在 Shizuku 里给本应用授权即可使用免 root 通道",
            hintEn = if (ok) "" else "Grant this app in Shizuku to use the rootless channel",
        )
    }

    private fun python(context: Context, zh: Boolean): Item {
        val path = runCatching { PythonRuntime.pythonPath(context) }.getOrNull()
        val ok = !path.isNullOrBlank()
        return Item(
            key = "python",
            titleZh = "内置 Python",
            titleEn = "Bundled Python",
            ok = ok,
            detailZh = path ?: "未安装",
            detailEn = path ?: "not installed",
            hintZh = if (ok) "" else "JADX 反编译脚本、LSP 补全、扩展插件都要它",
            hintEn = if (ok) "" else "Needed by JADX scripts, LSP completion and plugins",
        )
    }

    private fun nativeEngine(context: Context, zh: Boolean): Item {
        val libDir = runCatching { context.applicationInfo.nativeLibraryDir }.getOrNull()
        val so = libDir?.let { File(it, "librz_native.so") }
        // so != null 之后再访问 isFile/length：K2 不会因为 `so?.isFile == true` 就智能转换。
        val ok = so != null && so.isFile && so.length() > 0
        return Item(
            key = "engine",
            titleZh = "内置分析引擎（rizin）",
            titleEn = "Bundled engine (rizin)",
            ok = ok,
            detailZh = if (ok) "librz_native.so ${so!!.length() / 1048576} MiB" else "lib/${libDir?.substringAfterLast('/') ?: "?"} 下找不到 librz_native.so",
            detailEn = if (ok) "librz_native.so ${so!!.length() / 1048576} MiB" else "librz_native.so missing under ${libDir ?: "?"}",
            hintZh = if (ok) "" else "当前 ABI 的 APK 没有带引擎，请安装对应架构的包",
            hintEn = if (ok) "" else "This ABI build ships without the engine; install the matching architecture",
        )
    }

    private fun storage(context: Context, zh: Boolean): Item {
        val usable = runCatching { context.cacheDir.usableSpace }.getOrDefault(0L)
        val ok = usable > 50L * 1024 * 1024
        val text = "${usable / 1048576} MiB"
        return Item(
            key = "storage",
            titleZh = "可用空间",
            titleEn = "Free space",
            ok = ok,
            detailZh = if (ok) text else "$text（不足 50 MiB）",
            detailEn = if (ok) text else "$text (below 50 MiB)",
            hintZh = if (ok) "" else "备份、反编译、产物写入都可能因空间不足失败",
            hintEn = if (ok) "" else "Backups, decompilation and output writes may fail",
        )
    }
}
