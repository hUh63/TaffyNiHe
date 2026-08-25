package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.LinuxRootfs
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject

/**
 * 塔菲逆核: 内置 Linux rootfs 执行（Alpine / Ubuntu / 通用 Linux rootfs）。
 *
 * 零外部依赖: rootfs + proot 均内置 APK。root/Shizuku 用原生 chroot；
 * 无 root 用内置 proot 用户态模拟（Termux proot-distro 同款技术）。
 * 支持 apk/apt 包管理，可在完整 Linux 环境执行任意命令。
 */
object LinuxTool {

    val linux: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_linux",
            "【Linux 环境】在内置 Linux rootfs（Alpine/Ubuntu/通用）中执行命令。action=detect 查看内置发行版、解压状态、执行通道(chroot/proot)；action=install 解压指定发行版(distro, 默认 alpine, ubuntu 约 30MB 解压 96MB 需数秒)；action=shell 在 rootfs 内执行 shell 脚本(script 多行或 command 单条)；action=remove 删除已解压的发行版释放空间。root/Shizuku 走原生 chroot，无 root 走内置 proot（用户态模拟，可 apk/apt 装包）。",
            "Execute commands inside built-in Linux rootfs (Alpine/Ubuntu/generic). action=detect lists distros, install state and channel (chroot via root/Shizuku, or proot user-mode without root); action=install extracts a distro; action=shell runs a script inside the rootfs; action=remove deletes an extracted distro.",
            "dynamic", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf("detect (默认) | install | shell | remove", "detect", "install", "shell", "remove")
                "distro".oneOf("发行版名（默认 alpine；detect 可查看全部内置）", "alpine", "ubuntu")
                "script" str "shell: 多行 shell 脚本（与 command 二选一）"
                "command" str "shell: 单条命令（自动包装为 sh -c）"
                "timeoutSec" int "shell: 超时秒数(默认 60, 最大 600)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            return runCatching {
                when (args.str("action", "detect").ifBlank { "detect" }) {
                    "detect" -> detect(ctx)
                    "install" -> install(ctx, args)
                    "shell" -> shell(ctx, args)
                    "remove" -> remove(ctx, args)
                    else -> err("BAD_ACTION", "未知 action", "action", args.str("action"))
                }
            }.getOrElse { e ->
                err("LINUX_FAILED", "Linux 环境操作失败: ${e.message ?: e.javaClass.simpleName}", "action", args.str("action"))
            }
        }

        private fun detect(ctx: ToolContext): JSONObject {
            val distros = JSONArray()
            var installedCount = 0
            for (d in LinuxRootfs.distros(ctx.context)) {
                val installed = LinuxRootfs.installed(ctx.context, d.name)
                if (installed) installedCount++
                distros.put(JSONObject()
                    .put("name", d.name)
                    .put("pkgManager", d.pkgMgr)
                    .put("arch", d.arch)
                    .put("installed", installed)
                    .put("sizeMb", if (installed) LinuxRootfs.sizeMb(ctx.context, d.name) else 0)
                    .put("asset", "assets/linux/${d.name}.tar.gz (内置)"))
            }
            val ch = LinuxRootfs.channel(ctx.context)
            val root = com.soreverse.mcp.core.RootShell.isRootAvailable() || com.soreverse.mcp.core.PermissionManager.isShizukuGranted()
            val prootOk = LinuxRootfs.prootReady(ctx.context)
            return ok(JSONObject()
                .put("action", "detect")
                .put("distros", distros)
                .put("rootAvailable", root)
                .put("prootBuiltin", prootOk)
                .put("channel", ch ?: "none")
                .put("hint", when {
                    ch == "chroot" -> "root/Shizuku 通道: 原生 chroot，性能最优。可用 action=shell 执行（如: apk add python3 / apt update）"
                    ch == "proot" -> "无 root 通道: 内置 proot 用户态模拟。可用 action=shell 执行（如: apk add python3），装包体验与 root 基本一致"
                    else -> "无可用通道: rootfs 解压失败或 proot 未就绪"
                }))
        }

        private fun install(ctx: ToolContext, args: JSONObject): JSONObject {
            val distro = args.str("distro", "alpine").ifBlank { "alpine" }
            if (LinuxRootfs.installed(ctx.context, distro)) {
                return ok(JSONObject()
                    .put("action", "install")
                    .put("distro", distro)
                    .put("status", "already-installed")
                    .put("hint", "发行版 $distro 已解压就绪，可用 action=shell 进入。"))
            }
            // Ubuntu 首次解压较大, 提示耗时
            val isUbuntu = distro == "ubuntu"
            val before = System.currentTimeMillis()
            val dir = LinuxRootfs.ensureExtracted(ctx.context, distro)
            val elapsed = (System.currentTimeMillis() - before) / 1000
            if (dir == null) {
                return err("EXTRACT_FAILED", "解压失败（请确认内置资产完整或空间充足）", "distro", distro)
            }
            return ok(JSONObject()
                .put("action", "install")
                .put("distro", distro)
                .put("status", "installed")
                .put("rootfs", dir.absolutePath)
                .put("sizeMb", LinuxRootfs.sizeMb(ctx.context, distro))
                .put("elapsedSec", elapsed)
                .put("channel", LinuxRootfs.channel(ctx.context) ?: "none")
                .put("hint", if (isUbuntu) "Ubuntu base 解压完成（约 ${LinuxRootfs.sizeMb(ctx.context, distro)}MB）。建议先执行: apt update" else "Alpine 已就绪。建议先执行: apk update && apk add bash python3"))
        }

        private fun shell(ctx: ToolContext, args: JSONObject): JSONObject {
            val distro = args.str("distro", "alpine").ifBlank { "alpine" }
            val script = args.str("script").ifBlank { "" }
            val command = args.str("command").ifBlank { "" }
            if (script.isBlank() && command.isBlank()) {
                return err("INVALID_ARGUMENT", "需要 script 或 command 参数", "command", "")
            }
            val timeout = args.intValue("timeoutSec", 60).coerceIn(1, 600)
            val body = if (script.isNotBlank()) script else command
            val result = LinuxRootfs.exec(ctx.context, distro, body, timeoutSec = timeout.toLong())
                ?: return err("NOT_READY", "发行版 $distro 未解压或通道不可用，先执行 action=install", "distro", distro)
            val truncated = result.output.length > 30000
            return ok(JSONObject()
                .put("action", "shell")
                .put("distro", distro)
                .put("channel", result.channel)
                .put("exitCode", result.code)
                .put("output", result.output.take(30000))
                .put("truncated", truncated)
                .put("hint", if (result.code == 0) "执行完成（${result.channel} 通道）" else "执行失败(exit=${result.code})，详见 output"))
        }

        private fun remove(ctx: ToolContext, args: JSONObject): JSONObject {
            val distro = args.str("distro", "").ifBlank {
                return err("INVALID_ARGUMENT", "需要 distro 参数", "distro", "")
            }
            if (!LinuxRootfs.installed(ctx.context, distro)) {
                return ok(JSONObject().put("action", "remove").put("distro", distro).put("status", "not-installed"))
            }
            val okRemoved = LinuxRootfs.remove(ctx.context, distro)
            return ok(JSONObject()
                .put("action", "remove")
                .put("distro", distro)
                .put("status", if (okRemoved) "removed" else "remove-failed")
                .put("hint", "已释放空间，重新可用 action=install 解压。"))
        }
    }

    val ALL: List<ToolHandler> = listOf(linux)
}
