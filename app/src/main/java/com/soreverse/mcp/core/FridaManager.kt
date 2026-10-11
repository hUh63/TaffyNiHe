package com.soreverse.mcp.core

import android.content.Context

/**
 * 塔菲逆核: frida-server 生命周期管理(需 root)。
 *
 * frida-server 二进制以 libfrida_server.so 打包在 jniLibs/arm64-v8a(so 目录文件带执行权限)。
 * 启动流程(root):
 *   1. 从 nativeLibraryDir 把 libfrida_server.so 拷到 /data/local/tmp/frida-server
 *   2. chmod 755
 *   3. nohup 后台启动,默认监听 127.0.0.1:27042(端口可在 MCP 工具 taffy_frida_control action=port 自定义)
 * 之后玄星/MCP 工具通过 frida 协议(27042)控制 hook。
 */
object FridaManager {
    const val DEFAULT_PORT = 27042
    private const val REMOTE_PATH = "/data/local/tmp/frida-server"
    private const val SO_NAME = "libfrida_server.so"
    private const val PREFS = "taffy_frida"
    private const val KEY_PORT = "listen_port"

    /** 自定义监听端口(默认 27042); 非法值回落到默认。 */
    fun currentPort(context: Context): Int {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_PORT, DEFAULT_PORT)
        return if (p in 1024..65535) p else DEFAULT_PORT
    }

    /** 设置监听端口; 非法值(不在 1024..65535)拒绝并返回 false。 */
    fun setPort(context: Context, port: Int): Boolean {
        if (port !in 1024..65535) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_PORT, port).apply()
        return true
    }

    data class Status(
        val rootAvailable: Boolean,
        val binaryPresent: Boolean,
        val running: Boolean,
        val port: Int,
        val message: String,
    )

    /** 当前状态快照。 */
    fun status(context: Context): Status {
        val root = RootShell.isRootAvailable()
        val bin = RootShell.nativeBinary(context, SO_NAME) != null
        val port = currentPort(context)
        val running = if (root) isServerRunning() else false
        val msg = when {
            !bin -> "frida-server 二进制未打包"
            !root -> "未检测到 root(frida 需要 root)"
            running -> "frida-server 运行中 (:$port)"
            else -> "frida-server 已就绪,未启动"
        }
        return Status(root, bin, running, port, msg)
    }

    private fun isServerRunning(): Boolean {
        val r = RootShell.exec("pgrep -f frida-server || ps -A 2>/dev/null | grep -c '[f]rida-server'", timeoutSec = 8)
        return r.success && r.stdout.trim().isNotEmpty() && r.stdout.trim() != "0"
    }

    /** 启动 frida-server(root)。返回是否成功 + 说明。 */
    fun start(context: Context): Pair<Boolean, String> {
        if (!RootShell.isRootAvailable()) return false to "未检测到 root,无法启动 frida-server"
        val so = RootShell.nativeBinary(context, SO_NAME)
            ?: return false to "frida-server 二进制缺失($SO_NAME 未打包)"
        val port = currentPort(context)
        if (isServerRunning()) return true to "frida-server 已在运行 (:$port)"

        // 拷到 /data/local/tmp 并赋可执行权限(so 目录有时 noexec,拷出来最稳)
        val prep = RootShell.exec(
            "cp '${so.absolutePath}' $REMOTE_PATH && chmod 755 $REMOTE_PATH",
            timeoutSec = 20,
        )
        if (!prep.success) return false to "部署 frida-server 失败: ${prep.stderr.ifBlank { prep.stdout }}"

        // 后台启动(-D = daemon; -l 指定监听端口)
        RootShell.startDaemon("$REMOTE_PATH -D -l 127.0.0.1:$port")
        Thread.sleep(800)
        return if (isServerRunning()) {
            true to "frida-server 已启动 (:$port)"
        } else {
            false to "frida-server 启动后未检测到进程(检查 SELinux / 架构匹配)"
        }
    }

    /** 停止 frida-server(root)。 */
    fun stop(): Pair<Boolean, String> {
        if (!RootShell.isRootAvailable()) return false to "未检测到 root"
        RootShell.exec("pkill -f frida-server", timeoutSec = 10)
        Thread.sleep(400)
        return if (!isServerRunning()) true to "frida-server 已停止" else false to "停止失败,可能有残留进程"
    }
}
