package com.soreverse.mcp.core

import kotlinx.coroutines.CancellationException

/**
 * `runCatching` 的协程安全版本。
 *
 * `runCatching` 会把 `CancellationException` 也一起 catch 成 `Result.failure` —— 页面切走 /
 * LaunchedEffect 重启后，`withContext(IO)` 本该立刻取消，却被当成「业务失败」继续往下执行：
 * 继续调用 native、继续写已经销毁的 UI 状态，取消语义等于失效。
 *
 * 这里只捕获真正的异常，取消必须继续上抛。
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (t: Throwable) {
    Result.failure(t)
}
