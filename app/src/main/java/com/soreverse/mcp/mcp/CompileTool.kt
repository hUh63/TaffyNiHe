package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.RootShell
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 塔菲逆核: C/C++ 编译工具（方案 A — 调用设备外部编译器，不内置工具链）。
 *
 * 依赖设备上已安装的编译器（Termux clang/gcc 或 NDK 交叉编译器），
 * 通过 root 通道执行编译。本工具自身零体积增加（仅 Kotlin 代码）。
 *
 * action=detect  检测设备上的可用编译器（Termux clang/clang++/gcc/g++ 及 NDK 交叉编译器）。
 * action=compile 编译 C/C++ 源码为可执行文件或 .so（支持交叉编译目标架构）。
 */
object CompileTool {

    /** 候选编译器: 名称 -> 路径。 */
    private data class Candidate(val name: String, val path: String, val desc: String)

    private val hostCompilers = listOf(
        Candidate("clang", "/data/data/com.termux/files/usr/bin/clang", "Termux clang (本机)"),
        Candidate("clang++", "/data/data/com.termux/files/usr/bin/clang++", "Termux clang++ (本机)"),
        Candidate("gcc", "/data/data/com.termux/files/usr/bin/gcc", "Termux gcc (本机)"),
        Candidate("g++", "/data/data/com.termux/files/usr/bin/g++", "Termux g++ (本机)"),
        Candidate("cc", "/data/data/com.termux/files/usr/bin/cc", "Termux cc (本机)"),
    )

    /** NDK 交叉编译器: 目标 -> (名称, 路径, 说明)。 */
    private data class CrossCandidate(val target: String, val name: String, val path: String, val desc: String)

    private val crossCompilers = listOf(
        CrossCandidate("arm64", "aarch64-linux-android-clang", "/data/data/com.termux/files/usr/bin/aarch64-linux-android-clang", "NDK aarch64 (arm64)"),
        CrossCandidate("arm", "armv7a-linux-androideabi-clang", "/data/data/com.termux/files/usr/bin/armv7a-linux-androideabi-clang", "NDK armv7a (arm)"),
        CrossCandidate("x86_64", "x86_64-linux-android-clang", "/data/data/com.termux/files/usr/bin/x86_64-linux-android-clang", "NDK x86_64"),
        CrossCandidate("x86", "i686-linux-android-clang", "/data/data/com.termux/files/usr/bin/i686-linux-android-clang", "NDK i686 (x86)"),
    )

    val compile: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_compile",
            "【C/C++ 编译】调用设备上的外部编译器(Termux clang/gcc 或 NDK 交叉编译器)编译源码。action=detect 检测可用编译器; action=compile 编译 .c/.cpp 为可执行文件或 .so(支持交叉编译到 arm64/arm/x86_64/x86)。需 root + 设备已装编译器(如 Termux 的 clang)。编译产物输出到应用 filesDir/compiled/。",
            "Compile C/C++ source using an on-device external compiler (Termux clang/gcc or NDK cross-compiler). action=detect lists available compilers; action=compile compiles .c/.cpp into an executable or .so (cross-compile to arm64/arm/x86_64/x86). Requires root + an installed compiler (e.g. Termux clang). Outputs to app filesDir/compiled/.",
            "build", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf("detect (默认) | compile | setup", "detect", "compile", "setup")
                "source" str "compile: 源文件绝对路径(.c/.cpp)"
                "output" str "compile: 输出文件名(可选，缺省为源文件名去扩展名)"
                "target".oneOf("编译目标架构: native(默认,本机) | arm64 | arm | x86_64 | x86", "native", "arm64", "arm", "x86_64", "x86")
                "shared" bool "compile: true 编译成 .so 共享库(默认编译成可执行文件)"
                "flags" str "compile: 额外编译标志(可选，如 -O2 -Wall; -S 生成汇编/方案C)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            return runCatching {
                when (args.str("action", "detect").ifBlank { "detect" }) {
                    "detect" -> detect()
                    "compile" -> compile(ctx, args)
                    "setup" -> setup()
                    else -> err("BAD_ACTION", "未知 action", "action", args.str("action"))
                }
            }.getOrElse { e -> err("INTERNAL_ERROR", "处理失败: ${e.message}", "action", args.str("action")) }
        }

        // ── 方案 B/C 务实落地: 通过 root 一键安装编译器到 Termux ──
        // 不把 60-80MB 工具链塞进 APK, 而是首次使用时经 root 在 Termux 里 pkg install。
        // C(仅汇编/IR) 由 compile 的 flags=-S/-E 直接支持, 无需单独动作。

        private fun setup(): JSONObject {
            if (!RootShell.isRootAvailable()) {
                return err("NO_ROOT", "未检测到 root, 无法自动安装编译器。请手动在 Termux 中执行: pkg install clang", "action", "setup")
            }
            val termuxPrefix = "/data/data/com.termux/files/usr"
            val pkgBin = "$termuxPrefix/bin/pkg"
            if (!isExecutable(pkgBin)) {
                return err("NO_TERMUX",
                    "未检测到 Termux(找不到 $pkgBin)。请先安装 Termux(F-Droid/GitHub 下载), 再回来执行 setup; 或在 Termux 中手动执行: pkg install clang",
                    "action", "setup")
            }
            // 用 Termux 的环境执行 pkg install(需要 PREFIX/PATH/TMPDIR/HOME)
            val env = "PREFIX=$termuxPrefix PATH=$termuxPrefix/bin:/system/bin TMPDIR=$termuxPrefix/tmp HOME=/data/data/com.termux/files/home"
            val r = RootShell.exec("env $env $pkgBin install -y clang 2>&1", timeoutSec = 600)
            val clangOk = isExecutable("$termuxPrefix/bin/clang")
            return ok(JSONObject()
                .put("action", "setup")
                .put("success", clangOk)
                .put("clangInstalled", clangOk)
                .put("exitCode", r.code)
                .put("output", (r.stdout + "\n" + r.stderr).take(1500))
                .put("hint", if (clangOk)
                    "clang 安装成功, 现在可用 action=compile 编译(或 action=compile flags=-S 生成汇编/方案C)。"
                else
                    "安装失败(常见原因: Termux 镜像源不通)。可在 Termux 里手动执行 pkg install clang 后重试 detect。"))
        }

        // ── 检测 ──

        private fun detect(): JSONObject {
            if (!RootShell.isRootAvailable()) {
                return err("NO_ROOT", "未检测到 root，无法探测编译器", "action", "detect")
            }
            val host = JSONArray()
            for (c in hostCompilers) {
                if (isExecutable(c.path)) {
                    val ver = versionOf(c.path)
                    host.put(JSONObject().put("name", c.name).put("path", c.path).put("desc", c.desc).put("version", ver))
                }
            }
            val cross = JSONArray()
            for (c in crossCompilers) {
                if (isExecutable(c.path)) {
                    cross.put(JSONObject().put("target", c.target).put("name", c.name).put("path", c.path).put("desc", c.desc))
                }
            }
            return ok(JSONObject()
                .put("action", "detect")
                .put("hostCompilers", host)
                .put("crossCompilers", cross)
                .put("hostCount", host.length())
                .put("crossCount", cross.length())
                .put("hint", if (host.length() == 0 && cross.length() == 0)
                    "未检测到任何编译器。请先安装 Termux 并执行 pkg install clang(或 clang lld ndk-multilib 用于交叉编译)。"
                else "检测到编译器，可用 action=compile 编译源码。"))
        }

        private fun isExecutable(path: String): Boolean =
            RootShell.exec("test -x \"$path\" && echo YES || echo NO", timeoutSec = 8).stdout.trim() == "YES"

        private fun versionOf(path: String): String =
            RootShell.exec("\"$path\" --version 2>&1 | head -1", timeoutSec = 10).stdout.trim().take(120)

        // ── 编译 ──

        private fun compile(ctx: ToolContext, args: JSONObject): JSONObject {
            if (!RootShell.isRootAvailable()) {
                return err("NO_ROOT", "未检测到 root，无法执行编译", "action", "compile")
            }
            val source = args.str("source").ifBlank { args.str("path") }
            if (source.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 source(源文件路径)", "source", "")
            val srcFile = File(source)
            if (!srcFile.isFile) return err("FILE_NOT_FOUND", "源文件不存在: $source", "source", source)

            val target = args.str("target", "native").ifBlank { "native" }
            val shared = args.optBoolean("shared", false)
            val flags = args.str("flags").ifBlank { "" }

            // 选择编译器
            val compilerPath: String = when (target) {
                "native" -> pickHostCompiler(srcFile)
                else -> crossCompilers.firstOrNull { it.target == target }?.path
                    ?: return err("NO_CROSS_COMPILER", "未检测到 $target 交叉编译器(需 Termux pkg install ndk-multilib)", "target", target)
            }
            if (compilerPath.isBlank()) {
                return err("NO_COMPILER", "未检测到编译器(需 Termux pkg install clang)", "target", target)
            }

            // 输出路径
            val outDir = File(ctx.context.filesDir, "compiled").apply { mkdirs() }
            val outName = args.str("output").ifBlank {
                srcFile.nameWithoutExtension + if (shared) ".so" else ""
            }
            val outFile = File(outDir, outName)

            // 组装编译命令
            val sharedFlag = if (shared) "-shared -fPIC" else ""
            val cmd = "\"$compilerPath\" $sharedFlag $flags \"${srcFile.absolutePath}\" -o \"${outFile.absolutePath}\" 2>&1"

            val r = RootShell.exec(cmd, timeoutSec = 120)
            val success = r.code == 0 && outFile.exists()
            return ok(JSONObject()
                .put("action", "compile")
                .put("success", success)
                .put("compiler", compilerPath)
                .put("target", target)
                .put("shared", shared)
                .put("output", outFile.absolutePath)
                .put("size", if (success) outFile.length() else 0)
                .put("exitCode", r.code)
                .put("stderr", r.stderr.take(500))
                .put("stdout", r.stdout.take(500))
                .put("hint", if (success) "编译成功，产物在 output 路径。" else "编译失败，详见 stderr/stdout。"))
        }

        private fun pickHostCompiler(srcFile: File): String {
            val wantCpp = srcFile.extension.equals("cpp", true) || srcFile.extension.equals("cc", true) || srcFile.extension.equals("cxx", true)
            val preferred = if (wantCpp) listOf("clang++", "g++", "clang", "gcc", "cc") else listOf("clang", "gcc", "cc", "clang++", "g++")
            for (name in preferred) {
                val c = hostCompilers.firstOrNull { it.name == name } ?: continue
                if (isExecutable(c.path)) return c.path
            }
            return ""
        }
    }

    val ALL: List<ToolHandler> = listOf(compile)
}
