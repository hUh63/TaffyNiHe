package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.ScriptRuntime
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.Undefined
import java.util.concurrent.atomic.AtomicInteger

// 塔菲逆核: codemode 脚本引擎(借鉴太墟 taixu 的 codemode)。
//
// 模型不再为多步编排一个一个往返 tool_call, 而是写一段 JS 脚本, 在沙箱里用
// tool(name, args) 直接串起任意已注册的 MCP 工具, log(...) 打点, return 输出结果。
//
// 沙箱边界(全部显式拒绝):
//   - 只用 Context.initSafeStandardObjects, 不注入任何宿主类 -> 没有 java / Packages /
//     getClass / JavaImporter / importPackage / loadClass
//   - 额外挂 ClassShutter 全部拒绝, 即使出现宿主对象也无法反射穿透
//   - 不提供 load / readFile / print / quit / spawn / setTimeout / XMLHttpRequest
//   - 无文件、无网络、无反射、无 sleep(调用即抛错, 防止脚本挂住)
//   - 关闭 E4X(FEATURE_E4X), 关掉 Rhino 的 XML 扩展语法
//   - 解释模式(optimizationLevel = -1), Android 上没有 JIT 也就没有字节码生成
//
// 超时/配额:
//   - 独立 daemon 线程执行, 主线程 join(timeoutMs); 超时 interrupt + 指令计数观察者兜底
//   - 指令计数观察者(threshold 10000)每次回调检查 deadline, 到点抛不可捕获的 Error
//   - toolCalls 超过 maxToolCalls 直接硬中断
//   - logs 累计 64KB、result 序列化后 256KB, 超出截断并标注 truncated
//
// 所有异常都被捕获转成 {ok:false, error:{...}}, 绝不冒泡到 MCP 服务端。
object ScriptTool {

    private const val SCRIPT_NAME = "taffy_script"
    private const val DEFAULT_TIMEOUT_MS = 8000
    private const val MAX_TIMEOUT_MS = 30000
    private const val DEFAULT_MAX_CALLS = 50
    private const val MAX_MAX_CALLS = 200
    private const val MAX_SOURCE_CHARS = 64 * 1024
    private const val MAX_LOG_BYTES = 64 * 1024
    private const val MAX_LOG_LINES = 2000
    private const val MAX_RESULT_BYTES = 256 * 1024
    private const val JOIN_SLACK_MS = 250
    private const val INSTRUCTION_THRESHOLD = 10_000

    // 脚本超时: 故意继承 Error(而不是 Exception), JS 侧的 try/catch 拦不住, 保证一定中止
    private class ScriptTimeout : Error("script timeout")

    // 工具调用配额超限: 同样不可被 JS catch, 直接中止脚本
    private class CallBudgetExceeded(limit: Int) : Error("maxToolCalls exceeded: $limit")

    // 空 reviver: NativeJSON.parse 必须给非 null reviver, 这里原样返回 value
    private object NoReviver : BaseFunction() {
        override fun call(cx: Context?, scope: Scriptable?, thisObj: Scriptable?, args: Array<out Any?>?): Any? =
            if (args != null && args.size > 1) args[1] else Undefined.instance
    }

    // 沙箱 ContextFactory: 每个脚本一次实例, deadline 内建, 观察者到点抛不可捕获 Error
    private class SandboxFactory(private val deadlineMs: Long) : ContextFactory() {
        override fun hasFeature(cx: Context, featureIndex: Int): Boolean =
            if (featureIndex == Context.FEATURE_E4X) false else super.hasFeature(cx, featureIndex)

        override fun makeContext(): Context {
            val cx = super.makeContext()
            cx.setOptimizationLevel(-1)
            cx.setLanguageVersion(Context.VERSION_ES6)
            cx.setInstructionObserverThreshold(INSTRUCTION_THRESHOLD)
            cx.setClassShutter(ClassShutter { false })
            return cx
        }

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            if (System.currentTimeMillis() > deadlineMs) throw ScriptTimeout()
        }
    }

    // 日志出口: 逐行累计, 字节数与行数双上限(防小行刷爆内存)
    private class LogSink {
        private val lines = ArrayList<String>()
        private var bytes = 0
        @Volatile var truncated = false
            private set

        @Synchronized
        fun add(line: String) {
            if (truncated) return
            if (lines.size >= MAX_LOG_LINES) {
                truncated = true
                return
            }
            val size = line.toByteArray(Charsets.UTF_8).size + 1
            if (bytes + size > MAX_LOG_BYTES) {
                truncated = true
                return
            }
            bytes += size
            lines.add(line)
        }

        @Synchronized
        fun snapshot(): JSONArray {
            val arr = JSONArray()
            lines.forEach { arr.put(it) }
            return arr
        }
    }

    // 单次执行的结果槽: 工作线程写, 主线程读
    private class Outcome {
        @Volatile var resultText: String? = null
        @Volatile var failureCode: String = "SCRIPT_ERROR"
        @Volatile var failure: String? = null
        @Volatile var calls: Int = 0
        @Volatile var resultTruncated: Boolean = false
    }

    // JS 值 -> JSON 文本(用沙箱自带的 JSON.stringify, 不引宿主 Java 序列化)
    private fun stringifyJs(cx: Context, scope: Scriptable, value: Any?): String = try {
        val r = NativeJSON.stringify(cx, scope, value, null, null)
        if (r == null || r === Undefined.instance) "null" else Context.toString(r)
    } catch (t: Throwable) {
        java.lang.String.valueOf(value)
    }

    // 工具调用的失败信封(与项目 err 结构保持一致, 脚本可读 r.ok / r.error.code 继续跑)
    private fun failureJson(code: String, message: String): String = JSONObject()
        .put("ok", false)
        .put("error", JSONObject()
            .put("code", code)
            .put("message", message)
            .put("severity", "error")
            .put("recoverable", true))
        .put("nextActions", JSONArray())
        .toString()

    // 真正落地的工具调用: 走 ToolCatalog.byName 的分发表, 任何异常都转成 ok:false 的 JSON 原文
    private fun invokeTool(ctx: ToolContext, name: String, argJson: String): String {
        if (name == "taffy_script") return failureJson("FORBIDDEN", "不允许在脚本内递归调用 taffy_script")
        val handler = ToolCatalog.byName[name]
            ?: return failureJson("UNKNOWN_TOOL", "未注册的工具: $name")
        return try {
            val argObj = if (argJson.isBlank()) JSONObject() else JSONObject(argJson)
            handler.handle(ctx, argObj).toString()
        } catch (t: Throwable) {
            failureJson("TOOL_FAILED", "工具 $name 执行失败: ${t.javaClass.simpleName}: ${t.message ?: "no message"}")
        }
    }

    // tool(name, args) / toolJson(name, args)
    private class JsToolFunction(
        private val ctx: ToolContext,
        private val asJson: Boolean,
        private val calls: AtomicInteger,
        private val maxCalls: Int,
    ) : BaseFunction() {

        override fun getFunctionName(): String = if (asJson) "toolJson" else "tool"

        override fun call(cx: Context?, scope: Scriptable?, thisObj: Scriptable?, args: Array<out Any?>?): Any? {
            val name = if (args != null && args.isNotEmpty()) Context.toString(args[0]).trim() else ""
            if (name.isEmpty()) {
                throw ScriptRuntime.constructError("Error", "tool(name, args): name 不能为空")
            }
            val argJson = if (args == null || args.size < 2 || args[1] === Undefined.instance) {
                "{}"
            } else {
                stringifyJs(cx!!, scope!!, args[1])
            }
            if (calls.incrementAndGet() > maxCalls) throw CallBudgetExceeded(maxCalls)
            val raw = invokeTool(ctx, name, argJson)
            if (!asJson) return raw
            return try {
                NativeJSON.parse(cx!!, scope!!, raw, NoReviver)
            } catch (t: Throwable) {
                raw
            }
        }
    }

    // log(...) : 字符串原样, 其它值 JSON.stringify
    private class JsLogFunction(private val sink: LogSink) : BaseFunction() {
        override fun getFunctionName(): String = "log"

        override fun call(cx: Context?, scope: Scriptable?, thisObj: Scriptable?, args: Array<out Any?>?): Any? {
            if (args == null || args.isEmpty()) {
                sink.add("")
                return Undefined.instance
            }
            val sb = StringBuilder()
            args.forEach { v ->
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(when {
                    v == null -> "null"
                    v is CharSequence -> v.toString()
                    v === Undefined.instance -> "undefined"
                    else -> stringifyJs(cx!!, scope!!, v)
                })
            }
            sink.add(sb.toString())
            return Undefined.instance
        }
    }

    private fun intArg(args: JSONObject, key: String, def: Int): Int {
        val raw = args.str(key).trim()
        if (raw.isEmpty()) return args.intValue(key, def)
        return raw.toDoubleOrNull()?.toInt() ?: args.intValue(key, def)
    }

    private fun render(value: Any?, cx: Context, scope: Scriptable): String = when {
        value == null -> "null"
        value === Undefined.instance || value === Scriptable.NOT_FOUND -> "null"
        value is CharSequence -> value.toString()
        else -> stringifyJs(cx, scope, value)
    }

    private fun parseInput(cx: Context, scope: Scriptable, raw: String): Any? {
        if (raw.isBlank()) return Undefined.instance
        return try {
            NativeJSON.parse(cx, scope, raw, NoReviver)
        } catch (t: Throwable) {
            raw
        }
    }

    private fun describeThrown(t: Throwable): String {
        val msg = when (t) {
            is RhinoException -> {
                val line = runCatching { t.lineNumber() }.getOrDefault(0)
                val src = runCatching { t.sourceName() }.getOrNull().orEmpty()
                val detail = runCatching { t.details() }.getOrNull().orEmpty()
                val text = if (detail.isNotBlank()) detail else (t.message ?: t.javaClass.simpleName)
                "$src:$line $text"
            }
            is StackOverflowError -> "脚本递归过深(StackOverflowError)"
            else -> "${t.javaClass.simpleName}: ${t.message ?: "no message"}"
        }.trim()
        return if (msg.length > 2000) msg.take(2000) + " ...(truncated)" else msg
    }

    // result: 能解析成 JSON 对象/数组就内嵌结构化值, 否则原样字符串; 超过 256KB 截断
    private fun wrapResult(text: String, outcome: Outcome): Any {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_RESULT_BYTES) {
            outcome.resultTruncated = true
            return String(bytes, 0, MAX_RESULT_BYTES, Charsets.UTF_8)
        }
        val trimmed = text.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val parsed = runCatching {
                if (trimmed.startsWith("{")) JSONObject(trimmed) else JSONArray(trimmed)
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return text
    }

    val tool: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_script",
            "【脚本编排 codemode】写一段 JS 脚本, 一次调用里串起多步工具(借鉴太墟 taixu 的 codemode)。"
                + "脚本内可用: tool(name,args) 调任意已注册工具并返回其 JSON 输出原文; toolJson(name,args) 同上但返回解析后的对象; "
                + "log(...) 追加日志(累计上限 64KB); 全局 args = 入参 input 解析后的对象(可选); 用 return 返回结果(对象会自动 JSON 序列化, 字符串原样)。"
                + "脚本跑在纯沙箱里: 没有 java/Packages/getClass 反射、没有文件读写、没有网络、没有 load/print/sleep, 所以别写 IO, 只能通过 tool() 借用工具能力。"
                + "工具失败(未注册/抛异常)不会中断脚本, 而是返回 {ok:false,error:{...}} 让你自己判断; 超时(默认 8000ms, 上限 30000)与工具调用配额(默认 50, 上限 200)超限则直接中止。"
                + "返回: ok / result(脚本 return 值) / logs(日志行) / elapsedMs / toolCalls(实际工具调用次数)。",
            "Run a JS script that orchestrates tool calls in one shot (codemode, borrowed from taixu). "
                + "Script API: tool(name,args) -> raw JSON string returned by any registered MCP tool; toolJson(name,args) -> parsed object (falls back to the raw string); "
                + "log(...) -> append log lines (64KB cap); global args -> the optional input object; use return to hand back the result (objects are JSON-serialized, strings pass through). "
                + "The script runs in a locked-down sandbox: no java/Packages/getClass reflection, no file or network access, no load/print/sleep. "
                + "A failing tool does not abort the script (it returns {ok:false,error:{...}}), but timeouts (default 8000ms, max 30000) and the tool-call budget (default 50, max 200) abort it. "
                + "Returns ok / result / logs / elapsedMs / toolCalls.",
            "runtime", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "source" str "JS 脚本源码；可用 tool(name,args) / toolJson(name,args) / log(...) ；用 return 返回结果"
                "input" str "可选：传给脚本的全局 args（JSON 字符串）"
                "timeoutMs" str "可选：执行超时毫秒，默认 8000，上限 30000"
                "maxToolCalls" str "可选：最多允许的工具调用次数，默认 50，上限 200"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val source = args.str("source")
            if (source.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 source(JS 脚本源码)", "source", "")
            if (source.length > MAX_SOURCE_CHARS) {
                return err("INVALID_ARGUMENT", "source 过大(上限 64KB)", "source", source.length)
            }
            val timeoutMs = intArg(args, "timeoutMs", DEFAULT_TIMEOUT_MS)
                .let { if (it <= 0) DEFAULT_TIMEOUT_MS else it.coerceAtMost(MAX_TIMEOUT_MS) }
            val maxCalls = intArg(args, "maxToolCalls", DEFAULT_MAX_CALLS)
                .let { if (it <= 0) DEFAULT_MAX_CALLS else it.coerceAtMost(MAX_MAX_CALLS) }
            val inputRaw = args.str("input").trim()

            val startedAt = System.currentTimeMillis()
            val logs = LogSink()
            val calls = AtomicInteger(0)
            val outcome = Outcome()
            val factory = SandboxFactory(startedAt + timeoutMs)

            val worker = Thread({
                try {
                    val cx = factory.enterContext()
                    try {
                        val scope = cx.initSafeStandardObjects(null, false)
                        scope.put("tool", scope, JsToolFunction(ctx, false, calls, maxCalls))
                        scope.put("toolJson", scope, JsToolFunction(ctx, true, calls, maxCalls))
                        scope.put("log", scope, JsLogFunction(logs))
                        scope.put("args", scope, parseInput(cx, scope, inputRaw))
                        // Rhino 的 compileFunction 只吃单个函数表达式(不接受顶层 return),
                        // 所以在同一行包一层 function(){ ... }, 让脚本里的 return 合法且行号与源码一致
                        val fn = cx.compileFunction(scope, "function(){" + source + "\n}", SCRIPT_NAME, 1, null)
                        val value = fn.call(cx, scope, scope, arrayOf<Any?>())
                        outcome.resultText = render(value, cx, scope)
                    } finally {
                        outcome.calls = calls.get()
                        runCatching { Context.exit() }
                    }
                } catch (t: Throwable) {
                    outcome.calls = calls.get()
                    outcome.failureCode = when (t) {
                        is ScriptTimeout -> "TIMEOUT"
                        is CallBudgetExceeded -> "LIMIT_EXCEEDED"
                        else -> "SCRIPT_ERROR"
                    }
                    outcome.failure = describeThrown(t)
                }
            }, "taffy-script")
            worker.isDaemon = true
            worker.start()

            try {
                worker.join((timeoutMs + JOIN_SLACK_MS).toLong())
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            val timedOut = worker.isAlive
            if (timedOut) {
                // 停不下来也没关系: daemon 线程 + 指令计数观察者会在 deadline 处自行中止
                runCatching { worker.interrupt() }
            }

            val elapsed = (System.currentTimeMillis() - startedAt).toInt()
            val callCount = if (timedOut) calls.get() else outcome.calls

            if (timedOut) {
                return err(
                    "TIMEOUT", "脚本执行超时(${timeoutMs}ms): 已强制中断", "timeoutMs", timeoutMs,
                    "elapsedMs" to elapsed, "toolCalls" to callCount, "logs" to logs.snapshot(),
                )
            }
            val failure = outcome.failure
            if (failure != null) {
                val message = if (outcome.failureCode == "TIMEOUT") {
                    "脚本执行超时(${timeoutMs}ms): 已强制中断(检查是否有死循环或过重的工具调用)"
                } else {
                    failure
                }
                return err(
                    outcome.failureCode, message, null, null,
                    "elapsedMs" to elapsed, "toolCalls" to callCount, "logs" to logs.snapshot(),
                )
            }

            val payload = JSONObject()
                .put("result", wrapResult(outcome.resultText ?: "null", outcome))
                .put("logs", logs.snapshot())
                .put("elapsedMs", elapsed)
                .put("toolCalls", callCount)
            if (outcome.resultTruncated || logs.truncated) {
                payload.put("truncated", true)
                if (outcome.resultTruncated) payload.put("resultTruncated", true)
                if (logs.truncated) payload.put("logsTruncated", true)
            }
            return ok(payload)
        }
    }

    val ALL = listOf(tool)
}
