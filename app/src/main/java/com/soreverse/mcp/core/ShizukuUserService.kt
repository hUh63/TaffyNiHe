package com.soreverse.mcp.core

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.ParcelFileDescriptor.AutoCloseInputStream
import android.os.ParcelFileDescriptor.AutoCloseOutputStream
import androidx.annotation.Keep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import kotlin.system.exitProcess

/**
 * 塔菲逆核: Shizuku UserService。
 *
 * 该类的实例由 Shizuku 服务进程以 **shell 权限(uid 2000)** 通过反射创建,
 * 因此内部 [Runtime.getRuntime().exec] 启动的命令(如 logcat)拥有 adb 级权限,
 * 可以读取全系统日志 —— 这是无 root 时 Logcat 查看器能显示日志的关键。
 *
 * 设计参照 LogFox(F0x1d/LogFox) 的 UserService:
 *  - 自定义 AIDL Stub 作为 binder(无需继承 Shizuku 基类)
 *  - Shizuku v13 需要 `constructor(Context)`, 用 @Keep 防止 R8 裁剪
 */
@Keep
class ShizukuUserService : IShizukuService.Stub() {

    @Keep
    constructor(context: Context) : this()

    private val serviceScopeJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceScopeJob)

    private var latestId = 0L
    private val currentProcesses = HashMap<Long, Process>()

    override fun destroy() {
        serviceScope.cancel()
        runBlocking { serviceScopeJob.join() }
        currentProcesses.values.forEach { runCatching { it.destroy() } }
        exitProcess(0)
    }

    /** 一次性命令: 返回 [exitCode, stdout, stderr]。 */
    override fun executeNow(command: String?): Array<String> = runBlocking(Dispatchers.IO) {
        if (command.isNullOrBlank()) return@runBlocking arrayOf("-1", "", "empty command")
        try {
            val process = Runtime.getRuntime().exec(command)
            val output = async { process.inputStream.readBytes().decodeToString() }
            val error = async { process.errorStream.readBytes().decodeToString() }
            val exitCode = process.waitFor()
            arrayOf(exitCode.toString(), output.await(), error.await())
        } catch (e: Exception) {
            arrayOf("-1", "", e.message ?: "exec failed")
        }
    }

    /** 启动持续进程, 返回进程 id。 */
    override fun execute(command: String?): Long {
        if (command.isNullOrBlank()) return -1
        return try {
            val processId = latestId++
            val process = Runtime.getRuntime().exec(command)
            currentProcesses[processId] = process
            processId
        } catch (e: Exception) {
            -1
        }
    }

    override fun processOutput(processId: Long): ParcelFileDescriptor? {
        val process = currentProcesses[processId] ?: return null
        return pipeFrom(process.inputStream)
    }

    override fun processError(processId: Long): ParcelFileDescriptor? {
        val process = currentProcesses[processId] ?: return null
        return pipeFrom(process.errorStream)
    }

    private fun pipeFrom(inputStream: InputStream): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        serviceScope.launch {
            AutoCloseOutputStream(pipe[1]).use { out ->
                try {
                    inputStream.copyTo(out)
                } catch (e: Exception) {
                    // 对端关闭(如 stop())属于正常路径
                }
            }
        }
        return pipe[0]
    }

    override fun processInput(processId: Long): ParcelFileDescriptor? {
        val process = currentProcesses[processId] ?: return null
        val pipe = ParcelFileDescriptor.createPipe()
        serviceScope.launch {
            AutoCloseInputStream(pipe[0]).use { inp ->
                try {
                    inp.copyTo(process.outputStream)
                } catch (e: Exception) {
                    // 对端关闭属于正常路径
                }
            }
        }
        return pipe[1]
    }

    override fun destroyProcess(processId: Long) {
        currentProcesses.remove(processId)?.let { runCatching { it.destroy() } }
    }

    /** 可取消的流拷贝(避免协程取消时挂死)。 */
    private suspend fun InputStream.copyTo(
        out: OutputStream,
        bufferSize: Int = DEFAULT_BUFFER_SIZE,
    ): Long = withContext(Dispatchers.IO) {
        var bytesCopied = 0L
        val buffer = ByteArray(bufferSize)
        var bytes = read(buffer)
        while (bytes >= 0 && isActive) {
            out.write(buffer, 0, bytes)
            bytesCopied += bytes
            bytes = read(buffer)
        }
        bytesCopied
    }
}
