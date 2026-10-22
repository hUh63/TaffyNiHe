package top.wkbin.taixu.ui.settings

import android.content.Context
import android.os.Build
import java.io.File
import org.koin.core.context.GlobalContext
import top.wkbin.taixu.core.database.AppDatabase

/**
 * 宿主移植后的运行时自检。
 *
 * 迁移到宿主工程时「能编译」和「能跑」是两件事：Koin 图是否装配、Room 是否建库、
 * PRoot/PTY/rizin 原生件是否随 APK 落地、当前 ABI 是否被支持——这些都只能在设备上验证。
 * 这里把可判定的项集中抽出来，供「系统自检」页直接展示，装机后一眼就能定位缺哪块。
 */
object TaixuDiagnostics {

    data class Item(
        val key: String,
        val title: String,
        val ok: Boolean,
        val detail: String,
        /** 失败是否致命（致命项失败时相关功能整块不可用）。 */
        val fatal: Boolean = false,
    )

    fun check(context: Context): List<Item> {
        val items = mutableListOf<Item>()
        val abis = Build.SUPPORTED_ABIS?.toList().orEmpty()
        val arm64 = abis.any { it == "arm64-v8a" }
        val nativeDir = runCatching { context.applicationInfo.nativeLibraryDir }.getOrDefault("")

        items += Item(
            key = "abi",
            title = "设备 ABI",
            ok = arm64,
            detail = if (arm64) {
                "arm64-v8a · 全部功能可用（${abis.joinToString()}）"
            } else {
                "当前为 ${abis.joinToString().ifBlank { "未知" }}：Linux 运行时与 PTY 终端依赖 ARM64 原生件，" +
                    "这两项在本机不可用，其余（Agent / 工作流 / 工作区 / 逆向工具）不受影响"
            },
            fatal = false,
        )

        items += Item(
            key = "koin",
            title = "依赖注入装配（Koin）",
            ok = runCatching { GlobalContext.get() }.isSuccess,
            detail = "太墟全部页面与后台服务通过 Koin 取依赖；装配失败时宿主会自动回退到塔菲原界面",
            fatal = true,
        )

        val roomOk = runCatching {
            val db: AppDatabase = GlobalContext.get().get()
            db.openHelper.writableDatabase.isOpen
        }.getOrDefault(false)
        items += Item(
            key = "room",
            title = "会话与记忆数据库（Room）",
            ok = roomOk,
            detail = "首次启动建库 / 版本迁移（当前 schema 74）；失败会导致会话、记忆、工作流无法持久化",
            fatal = true,
        )

        val proot = File(context.filesDir, "proot/proot")
        val prootAssets = runCatching { context.assets.list("proot")?.isNotEmpty() == true }.getOrDefault(false)
        items += Item(
            key = "proot",
            title = "PRoot 运行时",
            ok = proot.isFile || prootAssets,
            detail = if (proot.isFile) {
                "已解压到 ${proot.absolutePath}"
            } else if (prootAssets) {
                "APK 内置 PRoot 载荷，首次进入 Linux 时自动解压"
            } else {
                "未找到 PRoot 载荷：Linux 运行时不可用"
            },
            fatal = false,
        )

        val pty = File(nativeDir, "libpty_native.so")
        items += Item(
            key = "pty",
            title = "原生 PTY 终端",
            ok = pty.isFile,
            detail = if (pty.isFile) "libpty_native.so 已随 APK 落地" else "缺少 libpty_native.so：终端将退回 script 通道（无作业控制）",
            fatal = false,
        )

        val rizin = File(nativeDir, "librz_native.so")
        items += Item(
            key = "engine",
            title = "逆向引擎（rizin native）",
            ok = rizin.isFile,
            detail = if (rizin.isFile) "librz_native.so 已就绪（SO/APK 静态分析、CFG、反编译）" else "缺少 librz_native.so：逆向中枢的静态分析不可用",
            fatal = false,
        )

        val rootfsRoot = File(context.filesDir, "linux")
        val installed = runCatching { rootfsRoot.listFiles()?.count { it.isDirectory } ?: 0 }.getOrDefault(0)
        items += Item(
            key = "rootfs",
            title = "Linux 发行版",
            ok = installed > 0,
            detail = if (installed > 0) "已安装 $installed 个发行版" else "尚未安装发行版：设置 → Linux 环境 → 发行版管理 里安装（需联网）",
            fatal = false,
        )

        return items
    }
}
