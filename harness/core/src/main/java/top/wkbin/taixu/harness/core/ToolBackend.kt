package top.wkbin.taixu.harness.core

/** Execution port. The caller owns validation, permissions and durable intent/result commits. */
fun interface ToolBackend<Request, Result> {
    suspend fun execute(request: Request): Result
}
