package com.soreverse.mcp.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 原子写文件：先写**同目录**隐藏临时文件，再整体 rename 覆盖目标。
 *
 * 直接 `File.writeText` 会先 `open(TRUNC)` 把目标截断成 0 字节，然后才逐块写入：
 * 用户在编辑器里保存一个大文件、写到一半被系统杀进程 / 存储写满 / 电量掉 0，
 * 得到的就是「0 字节或半截」的文件 —— 原内容连同备份之外的部分一起没了。
 *
 * rename 在同一文件系统内是原子的，所以任何时刻目标文件要么是旧内容、要么是完整新内容。
 * 私有目录里的缓存/产物不必用它（写坏可重建），**用户文件与配置必须用**。
 */
object AtomicWrite {
    fun bytes(target: File, data: ByteArray): Boolean = runCatching {
        val dir = target.parentFile
        if (dir != null && !dir.exists()) dir.mkdirs()
        val tmp = File(dir, "." + target.name + ".tmp" + System.nanoTime())
        tmp.writeBytes(data)
        move(tmp, target)
    }.getOrDefault(false)

    fun text(target: File, text: String): Boolean =
        bytes(target, text.toByteArray(Charsets.UTF_8))

    private fun move(tmp: File, target: File): Boolean {
        // 首选「原子替换」
        val atomic = runCatching {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            true
        }.getOrDefault(false)
        if (atomic) return true
        // 某些文件系统（FUSE / exFAT / 部分 SD 卡）不支持 ATOMIC_MOVE
        val replaced = runCatching {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            true
        }.getOrDefault(false)
        if (replaced) return true
        // 最后兜底：renameTo，再不行就直接覆盖（并删临时文件）
        if (runCatching { tmp.renameTo(target) }.getOrDefault(false)) return true
        val ok = runCatching {
            target.writeBytes(tmp.readBytes())
            true
        }.getOrDefault(false)
        runCatching { tmp.delete() }
        return ok
    }
}
