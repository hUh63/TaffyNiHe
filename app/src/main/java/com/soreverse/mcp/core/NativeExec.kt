package com.soreverse.mcp.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 原生可执行通道（execve + jniLibs）。
 *
 * 背景：Android 10 起应用私有目录（filesDir / cacheDir / codeCacheDir）因 SELinux + W^X
 * 不再允许 `execve`，但 **APK 安装时解压出的 nativeLibraryDir 仍具可执行权限**。于是把
 * 交叉编译好的 Android bionic PIE 可执行文件命名成 `lib<name>.so` 放进 `jniLibs/<abi>/`，
 * 安装后就能被直接 `execve` —— 不需要 root、不需要 proot/chroot、不需要 rootfs，
 * 也不需要把二进制下载到可写目录再执行（那反而是被禁止的）。
 *
 * 塔菲自己的 `libcloudflared.so` 一直就是这么跑的（[com.soreverse.mcp.core.CloudflareTunnelManager]）。
 * 本对象把这条已验证的通道通用化：**扫描 / 判定 / 一次性执行 / 常驻后台 / 进程管理**。
 *
 * 安全边界（与 MCP 工具、设置页共用）：
 * 1. 只允许 nativeLibraryDir 下的文件，名字必须匹配 `^lib[A-Za-z0-9_]+\.so$`，
 *    并且 canonical path 必须确实落在该目录内（拒绝 `..`、符号链接逃逸）。
 * 2. 参数不经 shell：直接以 argv 数组 `execve`，天然免疫命令注入；参数另有长度/条数上限。
 * 3. 超时强制终止，stdout/stderr 有界收集（超出即截断并标记）。
 * 4. 执行前可校验 ELF 头（magic / e_type / e_machine），拒绝明显不是本机 ELF 的文件。
 */
object NativeExec {

    /** 只接受伪装成 .so 的可执行文件名。 */
    private val SO_NAME = Regex("^lib[A-Za-z0-9_]{1,60}\\.so$")

    const val MAX_ARGS = 64
    const val MAX_ARG_CHARS = 4096
    const val MAX_TIMEOUT_SEC = 900L
    const val MAX_OUTPUT_BYTES = 1024 * 1024

    /** 解析结果：足够判定「这是可执行文件还是共享库」。 */
    data class Elf(
        val isElf: Boolean = false,
        val elfType: Int = 0,      // 2=ET_EXEC, 3=ET_DYN
        val machine: Int = 0,      // 183=AArch64, 40=ARM, 62=x86_64, 3=x86
        val bits: Int = 0,         // 32 / 64
        val hasInterp: Boolean = false, // 存在 PT_INTERP → 动态可执行
        val entry: Long = 0,
    ) {
        /** 展示用类型名。 */
        val kind: String
            get() = when {
                !isElf -> "not-elf"
                elfType == 2 -> "exec"
                elfType == 3 && hasInterp -> "pie"
                elfType == 3 -> "pie-or-lib"
                else -> "lib"
            }

        /** 内核能直接 execve 的典型形态：ET_EXEC、或带 PT_INTERP 的 ET_DYN。 */
        val execCapable: Boolean get() = isElf && (elfType == 2 || (elfType == 3 && hasInterp))
    }

    data class ExecResult(
        val code: Int,
        val stdout: String,
        val stderr: String,
        val truncated: Boolean = false,
        val timedOut: Boolean = false,
        val pid: Long = -1L,
    )

    private class Proc(
        val pid: Long,
        val name: String,
        val command: String,
        val startedAt: Long,
        val process: Process,
        val logFile: File?,
    )

    private val daemons = ConcurrentHashMap<Long, Proc>()

    // ───────────────────────── 目录 / 解析 ─────────────────────────

    fun nativeDir(context: Context): File? =
        runCatching { context.applicationInfo?.nativeLibraryDir }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it) }
            ?.takeIf { it.isDirectory }

    /** 校验可执行文件名并解析为 nativeLibraryDir 内的真实文件（拒绝目录穿越）。 */
    fun resolve(context: Context, name: String): File? {
        val dir = nativeDir(context) ?: return null
        if (!SO_NAME.matches(name)) return null
        val f = File(dir, name)
        if (!f.isFile) return null
        val canonicalDir = runCatching { dir.canonicalFile }.getOrNull() ?: return null
        val canonical = runCatching { f.canonicalFile }.getOrNull() ?: return null
        if (canonical.parentFile != canonicalDir) return null
        return canonical
    }

    /** minSdk 26 没有 InputStream.readNBytes（Java 9 API 从 Android 33 才提供），自己读满或读尽。 */
    private fun readUpTo(input: InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (total < max) {
            val n = runCatching { input.read(buf, 0, minOf(buf.size, max - total)) }.getOrDefault(-1)
            if (n <= 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    /** 读取 ELF 头 + program header，判定 e_type / 机器 / 是否有 PT_INTERP。 */
    fun elfOf(file: File): Elf {
        val head = runCatching {
            file.inputStream().use { input -> readUpTo(input, 65536) }
        }.getOrNull() ?: return Elf()
        if (head.size < 64) return Elf()
        if (head[0] != 0x7F.toByte() || head[1] != 'E'.code.toByte() ||
            head[2] != 'L'.code.toByte() || head[3] != 'F'.code.toByte()
        ) return Elf()
        val bits = when (head[4].toInt() and 0xFF) {
            1 -> 32
            2 -> 64
            else -> return Elf()
        }
        val le = (head[5].toInt() and 0xFF) == 1
        fun u16(off: Int): Int = if (le) ((head[off].toInt() and 0xFF) or ((head[off + 1].toInt() and 0xFF) shl 8))
        else (((head[off].toInt() and 0xFF) shl 8) or (head[off + 1].toInt() and 0xFF))
        fun u32(off: Int): Long = if (le)
            ((head[off].toLong() and 0xFF) or ((head[off + 1].toLong() and 0xFF) shl 8) or ((head[off + 2].toLong() and 0xFF) shl 16) or ((head[off + 3].toLong() and 0xFF) shl 24))
        else
            (((head[off].toLong() and 0xFF) shl 24) or ((head[off + 1].toLong() and 0xFF) shl 16) or ((head[off + 2].toLong() and 0xFF) shl 8) or (head[off + 3].toLong() and 0xFF))
        fun u64(off: Int): Long {
            var v = 0L
            if (le) for (i in 7 downTo 0) v = (v shl 8) or (head[off + i].toLong() and 0xFF)
            else for (i in 0..7) v = (v shl 8) or (head[off + i].toLong() and 0xFF)
            return v
        }
        val elfType = u16(16)
        val machine = u16(18)
        val entry = if (bits == 64) u64(24) else u32(24)
        val phOff = if (bits == 64) u64(32) else u32(28)
        val phEntSize = if (bits == 64) u16(54) else u16(42)
        val phNum = if (bits == 64) u16(56) else u16(44)
        var hasInterp = false
        if (phEntSize > 0 && phNum in 1..512) {
            val need = phOff + phEntSize.toLong() * phNum
            val buf = if (need <= head.size) head else runCatching {
                file.inputStream().use { input -> readUpTo(input, minOf(need, 1L shl 20).toInt()) }
            }.getOrDefault(head)
            for (i in 0 until phNum) {
                val off = (phOff + phEntSize.toLong() * i).toInt()
                if (off < 0 || off + 4 > buf.size) break
                val pType = if (le)
                    ((buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8) or ((buf[off + 2].toInt() and 0xFF) shl 16) or ((buf[off + 3].toInt() and 0xFF) shl 24))
                else
                    (((buf[off].toInt() and 0xFF) shl 24) or ((buf[off + 1].toInt() and 0xFF) shl 16) or ((buf[off + 2].toInt() and 0xFF) shl 8) or (buf[off + 3].toInt() and 0xFF))
                if (pType == 3) { hasInterp = true; break } // PT_INTERP
            }
        }
        return Elf(isElf = true, elfType = elfType, machine = machine, bits = bits, hasInterp = hasInterp, entry = entry)
    }

    /** 当前 ABI 名（用于和 ELF e_machine 对照展示）。 */
    fun deviceAbi(): String = when (android.os.Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "arm64-v8a"
        "armeabi-v7a", "armeabi" -> "armeabi-v7a"
        "x86_64" -> "x86_64"
        "x86" -> "x86"
        else -> "unknown"
    }

    private fun abiMachines(abi: String): IntArray = when (abi) {
        "arm64-v8a" -> intArrayOf(183)
        "armeabi-v7a" -> intArrayOf(40)
        "x86_64" -> intArrayOf(62)
        "x86" -> intArrayOf(3)
        else -> IntArray(0)
    }

    private fun fileJson(dir: File, f: File): JSONObject {
        val elf = elfOf(f)
        val abi = deviceAbi()
        val machineMatch = abiMachines(abi).isEmpty() || elf.machine in abiMachines(abi)
        return JSONObject()
            .put("name", f.name)
            .put("path", f.absolutePath)
            .put("size", f.length())
            .put("executable", f.canExecute())
            .put("kind", elf.kind)
            .put("bits", elf.bits)
            .put("machine", elf.machine)
            .put("interp", elf.hasInterp)
            .put("entry", "0x%x".format(elf.entry))
            .put("elfOk", elf.isElf)
            .put("abiMatch", machineMatch)
            // 不做过度判定：权限位或 ELF 可执行形态任一成立即视为候选；真正能否 execve 由内核裁决
            .put("runnable", elf.isElf && machineMatch && (f.canExecute() || elf.execCapable))
            .put("directory", dir.absolutePath)
    }

    /** 列出 nativeLibraryDir 里的全部伪装二进制（含是否可执行 / ELF 判定）。 */
    fun list(context: Context): JSONObject {
        val dir = nativeDir(context) ?: return err("NATIVE_DIR_UNAVAILABLE", "nativeLibraryDir is not available on this device")
        val items = JSONArray()
        val files = dir.listFiles()?.filter { it.isFile && SO_NAME.matches(it.name) }?.sortedBy { it.name }.orEmpty()
        var runnable = 0
        files.forEach { f ->
            val json = fileJson(dir, f)
            if (json.optBoolean("runnable", false)) runnable++
            items.put(json)
        }
        return ok(JSONObject()
            .put("nativeLibraryDir", dir.absolutePath)
            .put("abi", deviceAbi())
            .put("extractNativeLibs", (runCatching { context.applicationInfo?.flags ?: 0 }.getOrDefault(0) and android.content.pm.ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS) != 0)
            .put("items", items)
            .put("total", items.length())
            .put("runnable", runnable)
            .put("usage", "action=run 需要 name + 可选 args；只允许该目录内名为 lib*.so 的文件，参数不经 shell。"))
    }

    fun probe(context: Context, name: String): JSONObject {
        val dir = nativeDir(context) ?: return err("NATIVE_DIR_UNAVAILABLE", "nativeLibraryDir is not available on this device")
        val f = resolve(context, name) ?: return err(
            "EXECUTABLE_NOT_FOUND",
            "No such executable in nativeLibraryDir (name must match lib*.so)",
            argument = "name", badValue = name,
        )
        return ok(fileJson(dir, f))
    }

    // ───────────────────────── 执行 ─────────────────────────

    private fun sanitizeArgs(args: List<String>): Pair<List<String>, String?> {
        if (args.size > MAX_ARGS) return emptyList<String>() to "too many arguments (max $MAX_ARGS)"
        for (a in args) {
            if (a.length > MAX_ARG_CHARS) return emptyList<String>() to "argument too long (max $MAX_ARG_CHARS chars)"
            if (a.contains('\u0000')) return emptyList<String>() to "argument contains NUL"
        }
        return args to null
    }

    private fun drain(input: InputStream, sink: StringBuilder, limit: Int, truncated: AtomicBoolean) {
        runCatching {
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (sink.length + n > limit) {
                    val room = (limit - sink.length).coerceAtLeast(0)
                    if (room > 0) sink.append(String(buf, 0, room, Charsets.UTF_8))
                    truncated.set(true)
                    // 继续读空，避免子进程因管道写满而阻塞
                } else {
                    sink.append(String(buf, 0, n, Charsets.UTF_8))
                }
            }
        }
    }

    /**
     * 一次性执行（前台等待）。stdin 非空时写入后关闭；超时 destroyForcibly。
     */
    fun run(
        context: Context,
        name: String,
        args: List<String> = emptyList(),
        stdin: String? = null,
        timeoutSec: Long = 30,
        maxOutputBytes: Int = 256 * 1024,
        workDir: File? = null,
        extraEnv: Map<String, String> = emptyMap(),
    ): ExecResult {
        val bin = resolve(context, name)
            ?: return ExecResult(-1, "", errText("EXECUTABLE_NOT_FOUND", "lib*.so not found in nativeLibraryDir: $name"))
        val elf = elfOf(bin)
        if (!elf.isElf) return ExecResult(-1, "", errText("NOT_ELF", "${bin.name} is not an ELF file"))
        val (safeArgs, argError) = sanitizeArgs(args)
        if (argError != null) return ExecResult(-1, "", errText("INVALID_ARGUMENTS", argError))
        val limit = maxOutputBytes.coerceIn(1024, MAX_OUTPUT_BYTES)
        val timeout = timeoutSec.coerceIn(1L, MAX_TIMEOUT_SEC)
        return runCatching {
            val cmd = listOf(bin.absolutePath) + safeArgs
            val pb = ProcessBuilder(cmd).directory(workDir ?: context.cacheDir).redirectErrorStream(false)
            if (extraEnv.isNotEmpty()) pb.environment().putAll(extraEnv)
            val process = pb.start()
            val out = StringBuilder(); val err = StringBuilder()
            val truncated = AtomicBoolean(false)
            val tOut = Thread { drain(process.inputStream, out, limit, truncated) }.apply { isDaemon = true; start() }
            val tErr = Thread { drain(process.errorStream, err, limit, truncated) }.apply { isDaemon = true; start() }
            if (stdin != null) {
                runCatching {
                    OutputStreamWriter(process.outputStream, Charsets.UTF_8).use { it.write(stdin) }
                }
            } else {
                runCatching { process.outputStream.close() }
            }
            val finished = process.waitFor(timeout, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                tOut.join(1500); tErr.join(1500)
                ExecResult(-1, out.toString(), err.toString() + "\n[timeout after ${timeout}s]", truncated.get(), timedOut = true)
            } else {
                tOut.join(2000); tErr.join(2000)
                ExecResult(process.exitValue(), out.toString(), err.toString(), truncated.get(), timedOut = false, pid = process.pid())
            }
        }.getOrElse { e ->
            ExecResult(-1, "", errText("EXEC_FAILED", e.message ?: e.javaClass.simpleName))
        }
    }

    private fun errText(code: String, message: String): String = "[$code] $message"

    /**
     * 常驻后台执行：立即返回 pid，输出重定向到 [logFile]（默认 cacheDir/native-exec-<pid>.log）。
     * 用于 frida-server、cloudflared 这类需要持续运行的进程。
     */
    fun startDaemon(context: Context, name: String, args: List<String> = emptyList(), logFile: File? = null): JSONObject {
        val bin = resolve(context, name) ?: return err("EXECUTABLE_NOT_FOUND", "lib*.so not found in nativeLibraryDir: $name", argument = "name", badValue = name)
        val elf = elfOf(bin)
        if (!elf.isElf) return err("NOT_ELF", "${bin.name} is not an ELF file", argument = "name", badValue = name)
        val (safeArgs, argError) = sanitizeArgs(args)
        if (argError != null) return err("INVALID_ARGUMENTS", argError, argument = "args")
        return runCatching {
            val cmd = listOf(bin.absolutePath) + safeArgs
            val log = logFile ?: File(context.cacheDir, "native-exec-${name.removeSuffix(".so")}.log")
            val pb = ProcessBuilder(cmd).directory(context.cacheDir).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            val process = pb.start()
            val pid = process.pid()
            daemons[pid] = Proc(pid, name, cmd.joinToString(" "), System.currentTimeMillis(), process, log)
            ok(JSONObject()
                .put("pid", pid)
                .put("name", name)
                .put("command", cmd.joinToString(" "))
                .put("logFile", log.absolutePath)
                .put("alive", process.isAlive))
        }.getOrElse { e ->
            err("EXEC_FAILED", e.message ?: e.javaClass.simpleName, argument = "name", badValue = name)
        }
    }

    fun processes(): JSONArray {
        val arr = JSONArray()
        val dead = mutableListOf<Long>()
        daemons.forEach { (pid, p) ->
            if (!p.process.isAlive) dead += pid
            arr.put(JSONObject()
                .put("pid", pid)
                .put("name", p.name)
                .put("command", p.command)
                .put("startedAt", p.startedAt)
                .put("uptimeSec", (System.currentTimeMillis() - p.startedAt) / 1000)
                .put("alive", p.process.isAlive)
                .put("exitCode", if (p.process.isAlive) JSONObject.NULL else runCatching { p.process.exitValue().toString() }.getOrDefault("?"))
                .put("logFile", p.logFile?.absolutePath ?: JSONObject.NULL))
        }
        dead.forEach { daemons.remove(it) }
        return arr
    }

    fun kill(pid: Long): JSONObject {
        val p = daemons[pid] ?: return err("PROCESS_NOT_FOUND", "No managed process with that pid", argument = "pid", badValue = pid)
        val okKill = runCatching {
            p.process.destroy()
            if (!p.process.waitFor(3, TimeUnit.SECONDS)) p.process.destroyForcibly()
            true
        }.getOrDefault(false)
        daemons.remove(pid)
        return if (okKill) ok(JSONObject().put("pid", pid).put("name", p.name).put("killed", true))
        else err("KILL_FAILED", "Failed to terminate pid $pid", argument = "pid", badValue = pid)
    }

    /** 读常驻进程日志尾部（有界）。 */
    fun tailLog(pid: Long, maxBytes: Int = 32 * 1024): JSONObject {
        val p = daemons[pid] ?: return err("PROCESS_NOT_FOUND", "No managed process with that pid", argument = "pid", badValue = pid)
        val f = p.logFile ?: return err("NO_LOG", "This process has no log file", argument = "pid", badValue = pid)
        if (!f.isFile) return ok(JSONObject().put("pid", pid).put("text", "").put("size", 0))
        val len = f.length()
        val take = minOf(len, maxBytes.toLong()).toInt()
        val text = runCatching {
            java.io.RandomAccessFile(f, "r").use { raf ->
                raf.seek(len - take)
                val buf = ByteArray(take)
                raf.readFully(buf)
                String(buf, Charsets.UTF_8)
            }
        }.getOrDefault("")
        return ok(JSONObject().put("pid", pid).put("text", text).put("size", len).put("truncated", len > take))
    }
}
