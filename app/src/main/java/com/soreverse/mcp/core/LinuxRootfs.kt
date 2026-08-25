package com.soreverse.mcp.core

import android.content.Context
import java.io.File
import java.io.GZIPInputStream
import java.io.InputStream

/**
 * 内置 Linux rootfs（Alpine / Ubuntu / 任意通用 Linux rootfs）——零外部依赖。
 *
 * 资产: assets/linux/<distro>.tar.gz（Alpine minirootfs 4MB / Ubuntu base 30MB / 任意 GNU tar.gz rootfs）
 * 通道: root/Shizuku → 原生 chroot（性能最优）；无 root → 内置 proot 用户态模拟（Termux 同款技术）。
 * 特点: 手写 tar 解析（ustar + GNU 长名 + PAX + symlink/hardlink），不引入任何第三方库；
 *       proot 运行时（proot + libtalloc/libandroid-shmem/libtermux-exec + loader）一并内置，
 *       无 root 即可进入完整 Linux 环境执行 apk/apt 等包管理。
 */
object LinuxRootfs {

    private const val ASSET_DIR = "linux"
    private const val EXTRACT_BASE = "linux"
    private const val PROOT_DIR_NAME = "proot"

    /** 内置发行版元数据（assets/linux/<name>.tar.gz 自动发现）。 */
    data class Distro(val name: String, val pkgMgr: String, val arch: String = "aarch64")

    private val knownDistros = mapOf(
        "alpine" to Distro("alpine", "apk"),
        "ubuntu" to Distro("ubuntu", "apt"),
    )

    private val cachedProotDir = java.util.concurrent.atomic.AtomicReference<File?>(null)

    // ------------------------------------------------------------------ 发现

    /** 内置发行版列表（assets 中实际存在的 tar.gz，忽略 proot 目录等非 rootfs 资产）。 */
    fun distros(context: Context): List<Distro> {
        val result = mutableListOf<Distro>()
        try {
            context.assets.list(ASSET_DIR)?.forEach { f ->
                if (!f.endsWith(".tar.gz")) return@forEach
                val base = f.removeSuffix(".tar.gz")
                val known = knownDistros[base]
                result.add(if (known != null) known else Distro(base, "未知(通用 rootfs)"))
            }
        } catch (_: Exception) { }
        if (result.isEmpty()) result.addAll(knownDistros.values)
        return result.sortedBy { it.name }
    }

    /** rootfs 解压根目录（filesDir/linux）。 */
    fun baseDir(context: Context): File = File(context.filesDir, EXTRACT_BASE)

    /** 某发行版解压目录（未解压时仍返回路径，installed() 判断是否就绪）。 */
    fun rootfsDir(context: Context, distro: String): File = File(baseDir(context), distro)

    /** 该发行版是否已解压就绪（bin/sh 存在）。 */
    fun installed(context: Context, distro: String): Boolean {
        val dir = rootfsDir(context, distro)
        return File(dir, "bin/sh").isFile || File(dir, "bin/busybox").isFile
    }

    /** 当前可用通道: "chroot"(root/Shizuku) | "proot"(无 root) | null(不可用)。 */
    fun channel(context: Context): String? {
        val root = RootShell.isRootAvailable() || PermissionManager.isShizukuGranted()
        return if (root) "chroot" else if (prootReady(context)) "proot" else null
    }

    /** 内置 proot 二进制是否就绪（无 root 进入 rootfs 的关键）。 */
    fun prootReady(context: Context): Boolean {
        val dir = ensureProot(context)
        return dir != null && File(dir, "proot").isFile
    }

    /**
     * 确保内置 proot 运行时从 assets 复制到 filesDir（assets 不可直接执行）。
     * 幂等: 已复制且版本一致时直接复用。
     */
    @Synchronized
    fun ensureProot(context: Context): File? {
        cachedProotDir.get()?.let { if (File(it, "proot").isFile) return it }
        val dir = File(baseDir(context), PROOT_DIR_NAME)
        val marker = File(dir, ".ready")
        if (File(dir, "proot").isFile && marker.isFile) {
            cachedProotDir.set(dir)
            return dir
        }
        return try {
            if (dir.exists()) dir.deleteRecursively()
            dir.mkdirs()
            val names = context.assets.list("$ASSET_DIR/$PROOT_DIR_NAME")
                ?: throw IllegalStateException("assets 缺少 proot 目录")
            for (n in names) {
                context.assets.open("$ASSET_DIR/$PROOT_DIR_NAME/$n").use { input ->
                    File(dir, n).outputStream().use { out -> input.copyTo(out) }
                }
            }
            // 可执行位
            for (n in listOf("proot", "loader", "loader32")) {
                runCatching { File(dir, n).setExecutable(true) }
            }
            File(dir, ".ready").writeText("1")
            cachedProotDir.set(dir)
            dir
        } catch (e: Exception) {
            AppLog.e("LinuxRootfs: proot extract failed", e)
            null
        }
    }

    private fun prootDir(context: Context): File? = ensureProot(context)

    // ------------------------------------------------------------------ 解压

    /**
     * 确保发行版解压完成（幂等）。首次解压 tar.gz → filesDir/linux/<distro>/。
     * 解压完成写 .ready 标记，避免重复校验。
     */
    @Synchronized
    fun ensureExtracted(context: Context, distro: String): File? {
        val dir = rootfsDir(context, distro)
        val marker = File(dir, ".ready")
        if (marker.isFile) return dir
        val asset = "$ASSET_DIR/$distro.tar.gz"
        val tmp = File(dir.parentFile, "$distro.tmp")
        // 清理残留
        if (tmp.exists()) tmp.deleteRecursively()
        tmp.mkdirs()
        return try {
            context.assets.open(asset).use { input ->
                TarGzExtractor.extractAll(input, tmp)
            }
            // Alpine: /bin/sh 是 busybox 符号链接, 解压后应存在；Ubuntu: /bin/sh -> dash 符号链接
            val sh = File(tmp, "bin/sh")
            if (!sh.isFile && !sh.exists()) {
                // 兼容无 /bin/sh 的 rootfs: 尝试 busybox
                if (!File(tmp, "bin/busybox").isFile) {
                    throw IllegalStateException("rootfs 缺少 /bin/sh 或 /bin/busybox")
                }
            }
            // 原子切换
            if (dir.exists()) dir.deleteRecursively()
            if (!tmp.renameTo(dir)) {
                // rename 失败(跨目录/占用)则复制
                tmp.copyRecursively(dir, overwrite = true)
                tmp.deleteRecursively()
            }
            // 写入 DNS 配置（rootfs 内无 resolv.conf 时 chroot/proot 后域名解析会失效）
            runCatching {
                val resolv = File(dir, "etc/resolv.conf")
                if (!resolv.exists() || resolv.readText().isBlank()) {
                    resolv.parentFile?.mkdirs()
                    resolv.writeText("nameserver 223.5.5.5\nnameserver 8.8.8.8\n")
                }
            }
            File(dir, ".ready").writeText(System.currentTimeMillis().toString())
            dir
        } catch (e: Exception) {
            AppLog.e("LinuxRootfs: extract $distro failed", e)
            tmp.deleteRecursively()
            null
        }
    }

    /** 删除已解压的 rootfs（释放空间）。 */
    fun remove(context: Context, distro: String): Boolean {
        val dir = rootfsDir(context, distro)
        if (!dir.exists()) return false
        dir.deleteRecursively()
        return !dir.exists()
    }

    /** 解压后占用空间（MB），未解压返回 0。 */
    fun sizeMb(context: Context, distro: String): Long {
        val dir = rootfsDir(context, distro)
        if (!dir.isDirectory) return 0
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1048576
    }

    // ------------------------------------------------------------------ 执行

    data class ExecResult(val code: Int, val output: String, val channel: String)

    /**
     * 在 rootfs 内执行 shell 脚本（不是单条命令——脚本写文件后执行，规避引号/注入问题）。
     *
     * @param distro 发行版名（alpine/ubuntu/任意已解压 rootfs）
     * @param script shell 脚本内容（多行）
     * @param timeoutSec 超时秒数
     */
    fun exec(context: Context, distro: String, script: String, timeoutSec: Long = 60): ExecResult? {
        val rootfs = ensureExtracted(context, distro) ?: return null
        val ch = channel(context) ?: return ExecResult(-1, "无可用通道（无 root 且内置 proot 未就绪）", "none")
        // 脚本写入 rootfs 内 /tmp（chroot 与 proot 均可访问）
        val tmpDir = File(rootfs, "tmp").apply { mkdirs() }
        val scriptFile = File(tmpDir, "taffy_${System.currentTimeMillis()}.sh")
        return try {
            scriptFile.writeText(script)
            val rel = "tmp/${scriptFile.name}"
            if (ch == "chroot") {
                // root 通道: 原生 chroot（挂载 /proc /dev /sys, 执行后卸载）
                val r = PermissionManager.exec(
                    "mount -t proc proc \"$rootfs/proc\" 2>/dev/null; " +
                    "mount -t sysfs sysfs \"$rootfs/sys\" 2>/dev/null; " +
                    "mount -o bind /dev \"$rootfs/dev\" 2>/dev/null; " +
                    "chroot \"$rootfs\" /bin/sh \"$rel\" 2>&1; rc=\$?; " +
                    "umount \"$rootfs/proc\" 2>/dev/null; umount \"$rootfs/sys\" 2>/dev/null; " +
                    "umount \"$rootfs/dev\" 2>/dev/null; exit \$rc",
                    timeoutSec = timeoutSec,
                )
                ExecResult(r.code, r.stdout, "chroot")
            } else {
                // 无 root 通道: 内置 proot（用户态模拟）
                val pd = prootDir(context) ?: return ExecResult(-1, "内置 proot 不可用", "none")
                val cmd = listOf(
                    File(pd, "proot").absolutePath,
                    "-r", rootfs.absolutePath,
                    "-0",                       // 模拟 root 身份（无 root 也能装包）
                    "-b", "/dev", "-b", "/proc", "-b", "/sys",
                    "--loader-path=" + File(pd, "loader").absolutePath,
                    "-w", "/",
                    "/bin/sh", rel,
                )
                val pb = ProcessBuilder(cmd)
                pb.environment().apply {
                    put("LD_LIBRARY_PATH", pd.absolutePath)
                    put("PROOT_TMP_DIR", File(baseDir(context), "tmp").apply { mkdirs() }.absolutePath)
                    put("TMPDIR", "/tmp")
                    put("PATH", "/usr/bin:/bin:/usr/sbin:/sbin")
                    put("HOME", "/root")
                }
                pb.redirectErrorStream(true)
                val proc = pb.start()
                val output = proc.inputStream.readBytes().decodeToString()
                val finished = proc.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
                if (!finished) {
                    proc.destroy()
                    ExecResult(-1, output, "proot")
                } else {
                    ExecResult(proc.exitValue(), output, "proot")
                }
            }
        } catch (e: Exception) {
            AppLog.e("LinuxRootfs: exec failed", e)
            ExecResult(-1, "执行异常: ${e.message}", ch)
        } finally {
            scriptFile.delete()
        }
    }
}

// ================================================================== tar.gz 解析
// 零依赖实现: 支持 ustar/GNU 格式、GNU 长文件名('L'/'K')、PAX 扩展('x')、符号链接/硬链接。
object TarGzExtractor {

    private const val BLOCK = 512

    fun extractAll(input: InputStream, dest: File) {
        val gz = GZIPInputStream(input, 64 * 1024)
        val header = ByteArray(BLOCK)
        var longName: String? = null
        var longLink: String? = null
        var paxPath: String? = null
        var paxLink: String? = null
        var sawAny = false

        while (true) {
            val read = readFull(gz, header)
            if (read < BLOCK) break
            if (isZeroBlock(header)) {
                // 连续两个零块 = 结束
                readFull(gz, header)
                if (isZeroBlock(header)) break
            }
            val type = header[156].toInt().toChar()
            var size = parseNumber(header, 124, 12)

            when (type) {
                'L' -> { longName = readDataString(gz, size).trimEnd('\u0000'); continue }
                'K' -> { longLink = readDataString(gz, size).trimEnd('\u0000'); continue }
                'x', 'g' -> {
                    val pax = readDataString(gz, size)
                    parsePax(pax, { paxPath = it }, { paxLink = it })
                    continue
                }
            }

            var name = entryName(header)
            if (longName != null) { name = longName; longName = null }
            if (paxPath != null) { name = paxPath; paxPath = null }
            if (name.isEmpty()) { skipData(gz, size); continue }
            sawAny = true

            // 路径穿越防护
            val clean = name.removePrefix("./").removePrefix("/")
            val target = File(dest, clean).normalize()
            if (!target.path.startsWith(dest.path)) { skipData(gz, size); continue }

            var linkName = String(header, 157, 100, Charsets.UTF_8).trimEnd('\u0000')
            if (longLink != null) { linkName = longLink; longLink = null }
            if (paxLink != null) { linkName = paxLink; paxLink = null }

            val mode = parseNumber(header, 100, 8)
            val isDir = type == '5' || (type != '2' && type != '1' && type != '0' && type != '\u0000' && type != '7' && clean.endsWith("/"))

            when (type) {
                '5' -> target.mkdirs()
                '2' -> { // 符号链接
                    target.parentFile?.mkdirs()
                    runCatching {
                        java.nio.file.Files.createSymbolicLink(target.toPath(), java.nio.file.Paths.get(linkName))
                    }.onFailure {
                        // 失败则复制目标内容（若目标在解压树内）或跳过
                        runCatching {
                            val resolved = File(dest, linkName.removePrefix("./"))
                            if (resolved.isFile) resolved.copyTo(target)
                            else if (resolved.isDirectory) target.mkdirs()
                        }
                    }
                }
                '1' -> { // 硬链接
                    target.parentFile?.mkdirs()
                    val src = File(dest, linkName.removePrefix("./"))
                    if (src.isFile) src.copyTo(target, overwrite = true)
                }
                '6' -> target.parentFile?.mkdirs() // fifo: 创建空文件占位
                else -> { // 常规文件
                    target.parentFile?.mkdirs()
                    if (size > 0) {
                        target.outputStream().use { out -> copyExactly(gz, out, size) }
                    } else {
                        target.writeBytes(ByteArray(0))
                    }
                }
            }
            // 可执行位
            if ((mode and 0b001_000_000) != 0L) {
                runCatching { target.setExecutable(true) }
            }
            // 对齐到 512
            skipPadding(gz, size)
        }
        if (!sawAny) throw IllegalStateException("空 tar 包")
    }

    private fun entryName(h: ByteArray): String {
        val name = String(h, 0, 100, Charsets.UTF_8).trimEnd('\u0000')
        val prefix = String(h, 345, 155, Charsets.UTF_8).trimEnd('\u0000')
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun parsePax(data: String, setPath: (String) -> Unit, setLink: (String) -> Unit) {
        var i = 0
        while (i < data.length) {
            val sp = data.indexOf(' ', i)
            if (sp < 0) break
            val len = data.substring(i, sp).toIntOrNull() ?: break
            if (len <= 0 || i + len > data.length) break
            val rec = data.substring(sp + 1, i + len - 1) // 去尾部换行
            val eq = rec.indexOf('=')
            if (eq > 0) {
                val key = rec.substring(0, eq)
                val value = rec.substring(eq + 1)
                when (key) {
                    "path" -> setPath(value)
                    "linkpath" -> setLink(value)
                }
            }
            i += len
        }
    }

    private fun parseNumber(b: ByteArray, off: Int, len: Int): Long {
        // GNU base-256 大数
        if (b[off].toInt() and 0x80 != 0) {
            var v = (b[off].toLong() and 0x7F)
            for (i in off + 1 until off + len) v = (v shl 8) or (b[i].toLong() and 0xFF)
            return v
        }
        val s = String(b, off, len, Charsets.US_ASCII).trimEnd('\u0000', ' ')
        if (s.isEmpty()) return 0
        return s.toLongOrNull(8) ?: 0
    }

    private fun readFull(input: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }

    private fun readDataString(input: InputStream, size: Long): String {
        val bytes = ByteArray(size.toInt())
        var off = 0
        while (off < bytes.size) {
            val n = input.read(bytes, off, bytes.size - off)
            if (n < 0) break
            off += n
        }
        skipPadding(input, size)
        return String(bytes, Charsets.UTF_8)
    }

    private fun copyExactly(input: InputStream, out: java.io.OutputStream, size: Long) {
        val buf = ByteArray(64 * 1024)
        var remaining = size
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
    }

    private fun skipData(input: InputStream, size: Long) {
        val buf = ByteArray(64 * 1024)
        var remaining = size
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) break
            remaining -= n
        }
        skipPadding(input, size)
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val pad = ((size + BLOCK - 1) / BLOCK * BLOCK - size).toInt()
        var left = pad
        val buf = ByteArray(64)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(left, buf.size))
            if (n < 0) break
            left -= n
        }
    }

    private fun isZeroBlock(b: ByteArray): Boolean {
        for (x in b) if (x.toInt() != 0) return false
        return true
    }
}
