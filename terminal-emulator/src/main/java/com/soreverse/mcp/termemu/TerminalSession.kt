package com.soreverse.mcp.termemu

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 终端会话内核：管理一个长驻子进程（`sh` / `python -i` / 任意 CLI），
 * 把它的输出经 [AnsiFilter] 清洗后回调给 UI，并提供写入/中断/关闭。
 *
 * 对标 Xed-Editor 的 `terminal-emulator` 模块（Termux 的 TerminalSession），
 * 但只保留我们真正需要的能力：**不绑定任何 UI**，因此 app 的终端页、编辑器控制台、
 * MCP 工具都能复用同一份会话实现（原先这些逻辑各自散在页面 composable 里，重复且易漏清理）。
 *
 * 线程模型：读线程为守护线程，随进程退出自然结束；所有 [Listener] 回调都在该线程上触发，
 * 调用方若需切主线程自行 `withContext(Dispatchers.Main)`。
 */
class TerminalSession(
    private val command: List<String>,
    private val env: Map<String, String> = emptyMap(),
    private val workDir: File? = null,
    private val maxBufferedChars: Int = 200_000,
    private val listener: Listener,
) {

    interface Listener {
        fun onOutput(text: String)
        fun onExit(code: Int)
    }

    private var proc: Process? = null
    private val alive = AtomicBoolean(false)

    val isAlive: Boolean get() = alive.get()

    /** 启动进程；返回是否成功。 */
    fun start(): Boolean {
        if (alive.get()) return true
        val p = runCatching {
            ProcessBuilder(command).apply {
                redirectErrorStream(true)
                env.forEach { (k, v) -> environment()[k] = v }
                workDir?.let { directory(it) }
            }.start()
        }.getOrNull() ?: return false

        proc = p
        alive.set(true)

        Thread({
            val buf = ByteArray(4096)
            try {
                while (alive.get()) {
                    val n = p.inputStream.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    val raw = String(buf, 0, n, Charsets.UTF_8)
                    val text = AnsiFilter.strip(raw)
                    if (text.isNotEmpty()) {
                        listener.onOutput(text.takeLast(maxBufferedChars))
                    }
                }
            } catch (_: Throwable) {
                // 进程被杀 / 流被关：按正常退出处理
            } finally {
                alive.set(false)
                val code = runCatching { p.exitValue() }.getOrDefault(-1)
                listener.onExit(code)
            }
        }, "term-session-${command.firstOrNull() ?: "shell"}").apply { isDaemon = true }.start()

        return true
    }

    /** 写入一行（自动补换行）。 */
    fun send(line: String) = sendRaw(if (line.endsWith("\n")) line else line + "\n")

    /** 原样写入。 */
    fun sendRaw(text: String) {
        val p = proc ?: return
        runCatching {
            p.outputStream.write(text.toByteArray(Charsets.UTF_8))
            p.outputStream.flush()
        }.onFailure { alive.set(false) }
    }

    /** 发送中断（SIGINT）：优先 destroy，超时后强杀。 */
    fun interrupt() {
        val p = proc ?: return
        runCatching { p.destroy() }
    }

    /** 等待退出（用于同步工具）。 */
    fun waitFor(seconds: Long): Int {
        val p = proc ?: return -1
        return runCatching {
            if (p.waitFor(seconds, TimeUnit.SECONDS)) p.exitValue() else -1
        }.getOrDefault(-1)
    }

    /** 关闭会话并等待回收。 */
    fun close() {
        val p = proc
        alive.set(false)
        proc = null
        runCatching { p?.destroy() }
        runCatching {
            if (p != null && !p.waitFor(600, TimeUnit.MILLISECONDS)) p.destroyForcibly()
        }
    }
}
