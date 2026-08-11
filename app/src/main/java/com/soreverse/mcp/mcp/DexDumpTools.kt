package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.BinaryManager
import com.soreverse.mcp.core.PermissionManager
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONObject

/**
 * 塔菲逆核: eBPFDexDumper —— 官方 eBPF DEX dump 工具封装（chinleez/eBPFDexDumper-rs）。
 *
 * 在已 Root 的 ARM64 设备上从 ART 运行时抓取真实 DEX（uprobe 挂 ART 生命周期探针），
 * 支持：dump（抓 DEX + 方法字节码记录）/ fix（字节码回填修复）/ dumpso（native so dump）/
 * offsets（ART 布局检测，用于 ROM 适配）。
 *
 * 环境要求：ARM64 + Root + eBPF（内核 BPF 可用）。
 */
object DexDumpTools {

    private const val BIN = "/data/local/tmp/eBPFDexDumper"
    private const val OUT_DIR = "/data/local/tmp/dex_out"

    val dexdump: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta("taffy_dexdump",
            "【eBPF DEX dump】官方 eBPFDexDumper（从 ART 运行时抓取真实 DEX，方法字节码回填）。action=probe 检测环境; action=install 下载部署二进制; action=dump 抓取 DEX(-n 包名, 可选 -o 输出目录); action=fix 修复 DEX(-d 目录); action=dumpso dump native so(-n 包名, 可选 --lib 过滤); action=offsets 检测 ART 布局; action=read 读取输出目录文件列表与关键文件。需 Root + eBPF。",
            "Official eBPFDexDumper (grab real DEX from ART runtime). probe; install; dump (-n package); fix (-d dir); dumpso; offsets (ART layout); read output. Root + eBPF required.",
            "device", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf("probe | install | dump | fix | dumpso | offsets | read", "probe", "install", "dump", "fix", "dumpso", "offsets", "read")
                "package" str "dump/dumpso: 目标应用包名"
                "dir" str "fix/read: 输出目录(默认 /data/local/tmp/dex_out)"
                "lib" str "dumpso: 指定 so 库名(可选, 如 libapp.so)"
                "output" str "dump: 输出目录(默认 /data/local/tmp/dex_out)"
                "timeoutSec" int "执行超时(秒, 默认 30, 最大 300)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val action = args.str("action", "probe")
            return runCatching {
                when (action) {
                    "probe" -> ok(BinaryManager.status(ctx.context, BinaryManager.DEXDUMP)
                        .put("root", PermissionManager.isRootAvailable())
                        .put("shizuku", PermissionManager.isShizukuGranted())
                        .put("bpfFs", PermissionManager.exec("ls /sys/fs/bpf 2>/dev/null | head -2").stdout)
                        .put("canUse", PermissionManager.isRootAvailable() && BinaryManager.isDeployed(BinaryManager.DEXDUMP))
                        .put("reason", if (!PermissionManager.isRootAvailable()) "eBPFDexDumper 基于 eBPF uprobe，需要 Root（Shizuku 无 eBPF 加载能力）。当前可用替代：taffy_dex_inspect（本地 dex 解析）/ DexAnalysisTools（Jadx 反编译）。" else "")
                        .put("fallbackTools", JSONArray().put(JSONObject()
                            .put("category", "DEX 静态分析（无需 root）")
                            .put("tools", "taffy_dex_inspect / DexAnalysisTools 系列")
                            .put("description", "本地 dex 文件结构化检查与反编译，不需要 root 或 eBPF。")
                        ).put(JSONObject()
                            .put("category", "SO 静态分析（无需 root）")
                            .put("tools", "taffy_so_open + analyze_*")
                            .put("description", "基于内置 Rizin 后端的本地 ELF 静态分析。")
                        )))

                    "install" -> {
                        if (!PermissionManager.isRootAvailable()) {
                            return err("NO_ROOT", "需要 Root 权限", "action", "install")
                        }
                        if (!BinaryManager.isDownloaded(ctx.context, BinaryManager.DEXDUMP)) {
                            val dl = BinaryManager.download(ctx.context, BinaryManager.DEXDUMP)
                            if (!dl.optBoolean("ok")) return err("DOWNLOAD_FAILED", dl.optString("error"), "action", "install")
                        }
                        val dep = BinaryManager.deploy(ctx.context, BinaryManager.DEXDUMP)
                        if (!dep.optBoolean("ok")) return err("DEPLOY_FAILED", dep.optString("error"), "action", "install")
                        ok(JSONObject().put("action", "install").put("installed", true).put("path", BIN))
                    }

                    "dump" -> {
                        if (!PermissionManager.isRootAvailable()) return err("NO_ROOT", "需要 Root 权限", "action", "dump")
                        if (!BinaryManager.isDeployed(BinaryManager.DEXDUMP)) return err("NOT_INSTALLED", "未部署，先 install", "action", "dump")
                        val pkg = args.str("package")
                        if (pkg.isBlank()) return err("INVALID_ARGUMENT", "需要 package 参数", "action", "dump")
                        val out = args.str("output", OUT_DIR)
                        val timeout = args.intValue("timeoutSec", 30).coerceIn(5, 300)
                        val r = PermissionManager.exec(
                            "$BIN dump -n $pkg -o $out 2>&1 | tail -c 6000",
                            timeoutSec = timeout.toLong(),
                        )
                        ok(JSONObject()
                            .put("action", "dump")
                            .put("package", pkg)
                            .put("outputDir", out)
                            .put("exitCode", r.code)
                            .put("output", r.stdout.ifBlank { r.stderr }))
                    }

                    "fix" -> {
                        if (!PermissionManager.isRootAvailable()) return err("NO_ROOT", "需要 Root 权限", "action", "fix")
                        if (!BinaryManager.isDeployed(BinaryManager.DEXDUMP)) return err("NOT_INSTALLED", "未部署，先 install", "action", "fix")
                        val dir = args.str("dir", OUT_DIR)
                        val r = PermissionManager.exec("$BIN fix -d $dir 2>&1 | tail -c 6000", timeoutSec = 120)
                        ok(JSONObject().put("action", "fix").put("dir", dir).put("exitCode", r.code).put("output", r.stdout.ifBlank { r.stderr }))
                    }

                    "dumpso" -> {
                        if (!PermissionManager.isRootAvailable()) return err("NO_ROOT", "需要 Root 权限", "action", "dumpso")
                        if (!BinaryManager.isDeployed(BinaryManager.DEXDUMP)) return err("NOT_INSTALLED", "未部署，先 install", "action", "dumpso")
                        val pkg = args.str("package")
                        if (pkg.isBlank()) return err("INVALID_ARGUMENT", "需要 package 参数", "action", "dumpso")
                        val lib = args.str("lib")
                        val libArg = if (lib.isNotBlank()) " --lib $lib" else ""
                        val r = PermissionManager.exec("$BIN dumpso -n $pkg$libArg 2>&1 | tail -c 6000", timeoutSec = 60)
                        ok(JSONObject().put("action", "dumpso").put("package", pkg).put("exitCode", r.code).put("output", r.stdout.ifBlank { r.stderr }))
                    }

                    "offsets" -> {
                        if (!PermissionManager.isRootAvailable()) return err("NO_ROOT", "需要 Root 权限", "action", "offsets")
                        if (!BinaryManager.isDeployed(BinaryManager.DEXDUMP)) return err("NOT_INSTALLED", "未部署，先 install", "action", "offsets")
                        val r = PermissionManager.exec("$BIN offsets 2>&1 | tail -c 4000", timeoutSec = 30)
                        ok(JSONObject().put("action", "offsets").put("exitCode", r.code).put("output", r.stdout.ifBlank { r.stderr }))
                    }

                    "read" -> {
                        val dir = args.str("dir", OUT_DIR)
                        val files = PermissionManager.exec("find $dir -type f 2>/dev/null | head -50", timeoutSec = 10)
                        val dexList = PermissionManager.exec("ls -la $dir/final 2>/dev/null || ls -la $dir", timeoutSec = 10)
                        ok(JSONObject()
                            .put("action", "read")
                            .put("dir", dir)
                            .put("files", files.stdout.lines().filter { it.isNotBlank() })
                            .put("finalDir", dexList.stdout))
                    }

                    else -> err("UNKNOWN_ACTION", "未知 action: $action", "action", action)
                }
            }.getOrElse { e -> err("DEXDUMP_FAILED", "eBPFDexDumper 操作失败: ${e.message}", "action", action) }
        }
    }

    val ALL = listOf(dexdump)
}
