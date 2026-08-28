package com.soreverse.mcp.core

import android.content.Context
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 内置 Python 3.14 运行时（零依赖，无需 root/外部 app）。
 *
 * 资产: assets/terminal/python-runtime.zip（Termux cpython 3.14.6 arm64，首次使用解压到 filesDir）。
 * 执行: ProcessBuilder 直接运行解压后的 bin/python3，设 LD_LIBRARY_PATH=lib。
 * 优势: 无 root 也能执行 Python 脚本（解压在应用私有目录，不依赖 Termux）。
 */
object PythonRuntime {

    private const val ASSET = "terminal/python-runtime.zip"
    private const val EXTRACT_DIR = "python_runtime"

    @Volatile
    private var rootDir: File? = null

    @Volatile
    private var extracting = false

    /** 解压后根目录（含 bin/python3），失败返回 null。 */
    @Synchronized
    fun ensureExtracted(context: Context): File? {
        rootDir?.let { if (it.exists()) return it }
        val dir = File(context.filesDir, EXTRACT_DIR)
        val python = File(dir, "bin/python3")
        if (python.exists()) {
            rootDir = dir
            return dir
        }
        if (extracting) return null
        extracting = true
        try {
            context.assets.open(ASSET).use { input ->
                ZipInputStream(input).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val target = File(dir, entry.name)
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { out -> zis.copyTo(out) }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
            // 标记可执行
            runCatching { File(dir, "bin/python3").setExecutable(true) }
            rootDir = dir
            return dir
        } catch (e: Exception) {
            AppLog.e("PythonRuntime: extract failed", e)
            return null
        } finally {
            extracting = false
        }
    }

    /** Python 可执行文件路径（未解压/失败返回 null）。 */
    fun pythonPath(context: Context): String? {
        val dir = ensureExtracted(context) ?: return null
        val p = File(dir, "bin/python3")
        return if (p.isFile) p.absolutePath else null
    }

    /** 运行 Python 脚本（一行代码或脚本内容），返回输出。extraEnv: 附加环境变量（如扩展运行的 TAFFY_*）。 */
    fun run(
        context: Context,
        script: String,
        args: List<String> = emptyList(),
        timeoutSec: Long = 30,
        extraEnv: Map<String, String> = emptyMap(),
    ): Result {
        val dir = ensureExtracted(context) ?: return Result(-1, "", "内置 Python 解压失败")
        val python = File(dir, "bin/python3")
        if (!python.isFile) return Result(-1, "", "内置 Python 不存在: ${python.absolutePath}")
        return runCatching {
            val cmd = mutableListOf(python.absolutePath)
            cmd.addAll(args)
            val pb = ProcessBuilder(cmd)
            pb.environment().apply {
                put("LD_LIBRARY_PATH", File(dir, "lib").absolutePath)
                put("PATH", File(dir, "bin").absolutePath + ":/system/bin")
                put("HOME", dir.absolutePath)
                put("PYTHONHOME", dir.absolutePath)
                // jedi/parso 等支持库安装位置
                put("PYTHONPATH", File(dir, "lib/python3.14/site-packages").absolutePath)
                putAll(extraEnv)
            }
            val proc = pb.redirectErrorStream(true).start()
            proc.outputStream.use { it.write(script.toByteArray(Charsets.UTF_8)); it.flush() }
            proc.outputStream.close()
            val output = proc.inputStream.readBytes().decodeToString()
            val finished = proc.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) { proc.destroy(); return Result(-1, output, "执行超时(${timeoutSec}s)") }
            Result(proc.exitValue(), output, "")
        }.getOrElse { e -> Result(-1, "", "Python 执行失败: ${e.message}") }
    }

    /** 支持脚本版本号（变更时自动重复制）。 */
    private const val SUPPORT_VERSION = "2"

    /**
     * 确保支持脚本从 assets/editor/ 复制到 filesDir/editor_support/（幂等，版本变更自动更新）。
     * 返回脚本绝对路径，失败返回 null。
     * 内置: completion.py（jedi 补全）/ plugin_runner.py（扩展运行器）/ taffy_ext.py（扩展 API）/ taffy_cli.py（MCP CLI 副本）
     */
    fun supportScript(context: Context, name: String): String? {
        val dir = File(context.filesDir, "editor_support")
        val f = File(dir, name)
        val mark = File(dir, "$name.v")
        if (!f.isFile || mark.readTextOrNull() != SUPPORT_VERSION) {
            runCatching {
                dir.mkdirs()
                context.assets.open("editor/$name").use { input ->
                    f.outputStream().use { out -> input.copyTo(out) }
                }
                mark.writeText(SUPPORT_VERSION)
            }
        }
        return if (f.isFile) f.absolutePath else null
    }

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()

    data class Result(val code: Int, val output: String, val error: String) {
        val success: Boolean get() = code == 0
    }
}
