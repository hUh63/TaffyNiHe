package com.soreverse.mcp.core

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

/**
 * 塔菲逆核: root Shell 执行器。塔菲逆核面向逆向场景(用户默认已 root),
 * 许多动态工具(frida-server 启动、进程注入、/data 读写)需要 root 权限。
 *
 * 提供两种用法:
 *  - [exec] 一次性执行(su -c "cmd"),返回退出码 + stdout/stderr。
 *  - [isRootAvailable] 检测设备是否可用 root。
 *
 * 说明: 不缓存长驻 su 会话(不同 root 方案 Magisk/KernelSU 行为不同,一次性最稳)。
 * 后台常驻进程(如 frida-server)用 exec 里 `nohup ... &` 方式起。
 */
object RootShell {

    data class Result(val code: Int, val stdout: String, val stderr: String) {
        val success: Boolean get() = code == 0
    }

    @Volatile private var cachedRoot: Boolean? = null

    /**
     * 安全修复: 对 shell 命令参数进行清洗，防止命令注入攻击。
     * 仅允许字母、数字、点、下划线、连字符、斜杠和空格（用于多参数场景）。
     * 阻止单引号、分号、管道、反引号、$() 等 shell 元字符。
     */
    private val SAFE_CHARS = Regex("^[A-Za-z0-9._/ -]+$")

    /** 对用户输入进行安全清洗，返回适合在单引号内使用的字符串。 */
    fun sanitizeShellArg(input: String): String {
        // 移除所有单引号和其他 shell 特殊字符
        return input.filterNot { it == '\'' || it == '"' || it == ';' || it == '|' || it == '&' || it == '`' || it == '$' || it == '(' || it == ')' || it == '<' || it == '>' || it == '\n' || it == '\r' || it.code == 0 }
    }

    /** 验证用户输入是否只包含安全字符。 */
    fun isSafeArg(input: String): Boolean {
        return input.isNotBlank() && SAFE_CHARS.matches(input)
    }

    /** 检测 root 是否可用(缓存结果)。 */
    fun isRootAvailable(): Boolean {
        cachedRoot?.let { return it }
        val ok = runCatching {
            val r = exec("id", timeoutSec = 8)
            r.success && r.stdout.contains("uid=0")
        }.getOrDefault(false)
        cachedRoot = ok
        return ok
    }

    fun invalidateRootCache() { cachedRoot = null }

    /**
     * 用 su 执行一条命令。command 是要在 root shell 里跑的完整命令行。
     * @param timeoutSec 超时秒数,超时会强杀进程返回 code=-1。
     */
    fun exec(command: String, timeoutSec: Long = 30): Result {
        return runCatching {
            val process = ProcessBuilder("su").redirectErrorStream(false).start()
            val writer = OutputStreamWriter(process.outputStream)
            writer.write(command)
            writer.write("\n")
            writer.write("exit\n")
            writer.flush()
            writer.close()

            val out = StringBuilder()
            val err = StringBuilder()
            val outThread = Thread { BufferedReader(InputStreamReader(process.inputStream)).useLines { seq -> seq.forEach { out.appendLine(it) } } }
            val errThread = Thread { BufferedReader(InputStreamReader(process.errorStream)).useLines { seq -> seq.forEach { err.appendLine(it) } } }
            outThread.start(); errThread.start()

            val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return Result(-1, out.toString().trim(), (err.toString() + "\n[timeout after ${timeoutSec}s]").trim())
            }
            outThread.join(2000); errThread.join(2000)
            Result(process.exitValue(), out.toString().trim(), err.toString().trim())
        }.getOrElse { e ->
            Result(-1, "", e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 后台启动一个常驻进程(root)。用 nohup + & 脱离,立即返回。
     * 适合 frida-server 这类需要一直跑的守护进程。
     */
    fun startDaemon(command: String): Result {
        // setsid + nohup 让进程脱离当前 su 会话,不随 exit 结束
        val wrapped = "nohup $command >/dev/null 2>&1 &"
        return exec(wrapped, timeoutSec = 10)
    }

    /** 从 nativeLibraryDir 取伪装成 .so 的二进制路径(SOMCP 的 cloudflared 同套技巧)。 */
    fun nativeBinary(context: android.content.Context, soName: String): File? {
        val dir = context.applicationInfo?.nativeLibraryDir ?: return null
        val f = File(dir, soName)
        return f.takeIf { it.exists() }
    }
}
