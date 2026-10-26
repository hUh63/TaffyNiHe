package com.soreverse.mcp.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * `scope.launch` 的兜底版本：块内任何未捕获异常只记日志，不再把整个进程打崩。
 *
 * `rememberCoroutineScope()` / `lifecycleScope` 都没有异常处理器 —— 块里漏一个 catch，
 * 异常就走 `Thread.uncaughtExceptionHandler`，用户看到的就是闪退。而工具调用、文件读写、
 * JSON 解析全在这些块里。
 *
 * 取消照常上抛：`CancellationException` 是控制流，不是业务失败。
 */
fun CoroutineScope.safeLaunch(
    context: CoroutineContext = EmptyCoroutineContext,
    block: suspend CoroutineScope.() -> Unit,
): Job = launch(context) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        AppLog.e("协程异常已兜底: ${t.javaClass.simpleName}: ${t.message}")
    }
}
