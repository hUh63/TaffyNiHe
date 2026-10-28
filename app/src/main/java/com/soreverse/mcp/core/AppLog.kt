package com.soreverse.mcp.core

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.Locale

object AppLog {
    const val ACTION_LOG = "com.soreverse.mcp.LOG"
    const val EXTRA_LINE = "line"
    private const val MAX_LINES = 500
    private const val MAX_LINE_LEN = 4096
    private val lock = Any()
    private val lines = ArrayDeque<String>(MAX_LINES)
    // SimpleDateFormat 非线程安全，而 AppLog 是全局日志设施：任何线程都可能同时写。
    // 之前 format 调用在锁外，并发时会抛 ArrayIndexOutOfBounds / 输出错乱的时间戳。
    // DateTimeFormatter 不可变且线程安全，直接换掉，不需要再加锁。
    private val formatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US)
    private fun nowText(): String = LocalTime.now().format(formatter)
    private var app: Context? = null
    // 延迟 + 容错：部分运行环境（纯 JVM 单元测试、极早期初始化）没有 Android Looper。
    // 此时 mainHandler 为 null，分发退回同步路径 —— 日志设施绝不在初始化阶段抛错拖垮上层。
    private val mainHandler: Handler? by lazy { runCatching { Handler(Looper.getMainLooper()) }.getOrNull() }
    private val listeners = mutableListOf<(String) -> Unit>()
    @Volatile private var lastDelivered: String? = null

    fun init(context: Context) {
        app = context.applicationContext
    }

    fun addListener(l: (String) -> Unit) {
        synchronized(listeners) { listeners.add(l) }
    }

    fun removeListener(l: (String) -> Unit) {
        synchronized(listeners) { listeners.remove(l) }
    }

    @Deprecated("Use addListener/removeListener", ReplaceWith("addListener(f)"))
    fun setInprocListener(f: ((String) -> Unit?)?) {
        synchronized(listeners) {
            listeners.clear()
            if (f != null) listeners.add { s -> f(s) }
        }
    }

    fun i(message: String) = add("I", null, message)
    fun w(message: String) = add("W", null, message)
    fun e(message: String, throwable: Throwable? = null) = add("E", null, if (throwable == null) message else "$message: ${throwable.message}")

    /** 带 tag 的日志（与 android.util.Log 签名对齐，用于应用内精确日志收集）。 */
    fun d(tag: String, message: String) = add("D", tag, message)
    fun i(tag: String, message: String) = add("I", tag, message)
    fun w(tag: String, message: String) = add("W", tag, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) =
        add("E", tag, if (throwable == null) message else "$message: ${throwable.message}")

    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    fun clear() {
        synchronized(lock) { lines.clear() }
        deliver("__CLEAR__")
    }

    private fun add(level: String, tag: String?, message: String) {
        val minLevel = app?.let { runCatching { SettingsStore(it).logLevel }.getOrNull() } ?: "I"
        if (levelRank(level) < levelRank(minLevel)) return
        val raw = message
        val clipped = if (raw.length > MAX_LINE_LEN) raw.substring(0, MAX_LINE_LEN) + "…(${raw.length})" else raw
        val nextLine = if (tag.isNullOrBlank()) {
            "${nowText()} $level $clipped"
        } else {
            "${nowText()} $level $tag: $clipped"
        }
        synchronized(lock) {
            if (lines.size == MAX_LINES) lines.removeFirst()
            lines.addLast(nextLine)
        }
        deliver(nextLine)
    }

    private fun deliver(line: String) {
        val snapshot = synchronized(listeners) { listeners.toList() }
        if (snapshot.isEmpty()) {
            lastDelivered = null
            runCatching { app?.let { a -> a.sendBroadcast(Intent(ACTION_LOG).putExtra(EXTRA_LINE, line).setPackage(a.packageName)) } }
            return
        }
        if (line != "__CLEAR__" && line == lastDelivered) return
        lastDelivered = line
        val onMainThread = runCatching { Looper.myLooper() == Looper.getMainLooper() }.getOrDefault(false)
        val handler = mainHandler
        if (onMainThread || handler == null) {
            snapshot.forEach { runCatching { it(line) } }
        } else {
            handler.post {
                snapshot.forEach { runCatching { it(line) } }
            }
        }
    }

    private fun levelRank(level: String): Int = when (level) {
        "E" -> 3
        "W" -> 2
        else -> 1
    }
}
