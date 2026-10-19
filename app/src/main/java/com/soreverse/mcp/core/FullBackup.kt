package com.soreverse.mcp.core

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// 全量 ZIP 备份 / 恢复（借鉴太墟 taixu 的"本地 ZIP 备份"思路）。
//
// 覆盖内容：
//   prefs/        可导出的 SharedPreferences（逐 key 脱敏，敏感 key 直接跳过）
//   tasks/        filesDir/tasks
//   extensions/   filesDir/extensions
//   snapshots/    filesDir/edit-snapshots
//   workspaces/   filesDir/workspaces、filesDir/workspace、cacheDir/workspaces、cacheDir/picked_*
//   manifest.json 格式版本 / 创建时间 / App versionName / 条目统计 / 跳过项
//
// 加密复用 core.BackupCrypto（Argon2id + AES-256-GCM）：ZIP 字节先 Base64 再交给
// BackupCrypto.encrypt，因此不需要改动既有加密实现即可得到 .bak.enc。
// 本对象不打印任何密钥明文，摘要里只出现 key 名称。
object FullBackup {

    const val FORMAT_VERSION = 1
    const val MANIFEST_NAME = "manifest.json"

    private const val MAX_SINGLE_FILE_BYTES = 20L * 1024L * 1024L
    private const val MAX_ENTRIES = 20000
    private const val COPY_BUFFER = 64 * 1024

    // 敏感 key 片段：命中任意一个即脱敏（备份跳过、恢复也跳过）。只记录 key 名，不记录值。
    private val SECRET_PARTS = listOf(
        "token", "secret", "password", "passwd", "key",
        "apikey", "api_key", "credential", "cookie", "session",
    )

    data class CategoryStat(val name: String, val entries: Int, val skipped: Int)

    data class Summary(
        val path: String,
        val bytes: Long,
        val entries: Int,
        val skipped: List<String>,
        val encrypted: Boolean,
        val categories: List<CategoryStat> = emptyList(),
        val preRestorePath: String? = null,
    )

    data class Root(val prefix: String, val target: String, val category: String, val dir: File?)

    // ---------- 对外 API ----------

    suspend fun backup(
        context: Context,
        password: String? = null,
        onProgress: (String) -> Unit = {},
    ): Summary = withContext(Dispatchers.IO) {
        createArchive(context, password, "taffy-full-", onProgress)
    }

    suspend fun restore(
        context: Context,
        zipPath: String,
        password: String? = null,
        onProgress: (String) -> Unit = {},
    ): Summary = withContext(Dispatchers.IO) {
        val src = File(zipPath)
        require(src.isFile) { "备份文件不存在: $zipPath" }

        onProgress("读取备份包…")
        val encrypted = isEncryptedFile(src)
        val working: File
        if (encrypted) {
            val pwd = password?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("该备份已加密，请提供密码")
            onProgress("解密备份包…")
            // 解密同样要整包进内存（密文 + 明文 + Base64 中间态），超限直接拒绝而不是让系统杀进程。
            val restoreLimit = 200L * 1024 * 1024
            if (src.length() > restoreLimit) {
                throw IllegalStateException(
                    "备份包 ${src.length() / 1048576}MB，超过可恢复上限 ${restoreLimit / 1048576}MB（解密需要约 3 倍内存）。"
                )
            }
            val plain = Base64.decode(BackupCrypto.decrypt(src.readBytes(), pwd), Base64.DEFAULT)
            val tmp = File(context.cacheDir, "restore-" + stamp() + ".zip")
            FileOutputStream(tmp).use { it.write(plain) }
            plain.fill(0)
            working = tmp
        } else {
            working = src
        }

        try {
            val manifest = readManifest(working)
                ?: throw IllegalArgumentException("备份包缺少 $MANIFEST_NAME")
            val format = manifest.optInt("format", 0)
            require(format in 1..FORMAT_VERSION) { "不支持的备份格式版本: $format" }
            val roots = parseRoots(manifest)

            // 恢复前先备份当前状态，失败可回滚
            onProgress("恢复前先备份当前状态…")
            val pre = try {
                createArchive(context, null, "pre-restore-", onProgress)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                throw IllegalStateException("无法创建恢复前快照，已中止恢复: ${e.message.orEmpty()}", e)
            }

            var stagedDirs = emptyList<Pair<File, File>>()
            try {
                // 恢复前把受影响的目录整体挪到一边：只做覆盖写会让"备份里已删除的文件"复活，
                // 中断时目标目录也会停在"新旧混合"状态。
                stagedDirs = stageTargetDirs(context, roots)
                val result = restoreFrom(context, working, roots, onProgress)
                // 成功：旧目录整体丢弃
                stagedDirs.forEach { (_, old) -> runCatching { old.deleteRecursively() } }
                Summary(
                    path = src.absolutePath,
                    bytes = src.length(),
                    entries = result.entries,
                    skipped = result.skipped,
                    encrypted = encrypted,
                    categories = result.categories,
                    preRestorePath = pre.path,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 用户主动取消不是失败：不触发回滚，但必须把挪走的原目录还原回去
                restoreStagedDirs(stagedDirs, keepNew = false)
                throw e
            } catch (e: Exception) {
                onProgress("恢复失败，回滚到恢复前快照…")
                val rollbackOk = runCatching {
                    val preManifest = readManifest(File(pre.path))
                    // manifest 读不出来时 restoreFrom 会把每一条都当"未知条目"跳过、
                    // 一个文件都不写，却仍返回成功 —— 旧实现据此报"已回滚"是假保证。
                    if (preManifest == null) return@runCatching false
                    val rr = restoreFrom(context, File(pre.path), parseRoots(preManifest), {})
                    rr.skipped.none { it.contains("写入失败") || it.contains("非法路径") }
                }.getOrDefault(false)
                if (rollbackOk) {
                    // 回滚用 pre 快照重写了目标目录，挪走的旧目录可以丢弃
                    restoreStagedDirs(stagedDirs, keepNew = true)
                }
                val tail = if (rollbackOk) {
                    "已回滚到 ${pre.path}"
                } else {
                    val salvage = stagedDirs.joinToString(", ") { it.second.absolutePath }
                    "自动回滚失败，请手动导入 ${pre.path}" +
                        if (salvage.isNotBlank()) "；恢复前的原目录已保留在 $salvage" else ""
                }
                throw IllegalStateException("恢复失败：${e.message.orEmpty()}；$tail", e)
            }
        } finally {
            if (working != src) working.delete()
        }
    }

    // 供 UI 复用同一套脱敏判断
    fun isSensitiveKey(key: String): Boolean {
        val k = key.lowercase(Locale.US)
        return SECRET_PARTS.any { k.contains(it) }
    }

    // ---------- 打包 ----------

    private fun createArchive(
        context: Context,
        password: String?,
        namePrefix: String,
        onProgress: (String) -> Unit,
    ): Summary {
        val dir = backupsDir(context).apply { mkdirs() }
        val tag = stamp()
        val encrypted = !password.isNullOrBlank()
        val outFile = File(dir, namePrefix + tag + if (encrypted) ".bak.enc" else ".zip")
        // 统一先写临时文件，成功后 rename 到最终名：
        // 直接写 outFile 时进程被杀会留下"半截包"（manifest 最后才写 → 恢复时报缺少 manifest）。
        val zipFile = File(dir, ".build-" + tag + ".tmp")
        val roots = roots(context)
        val createdAt = System.currentTimeMillis()
        val skipped = mutableListOf<String>()
        val maskedKeys = mutableListOf<String>()
        val categories = mutableListOf<CategoryStat>()
        var entries = 0

        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            onProgress("打包偏好设置…")
            val prefsStat = writePrefs(context, zos, skipped, maskedKeys)
            entries += prefsStat.entries
            if (prefsStat.entries > 0 || prefsStat.skipped > 0) categories += prefsStat

            for (root in roots) {
                val base = root.dir ?: continue
                if (!base.isDirectory) continue
                onProgress("打包 ${root.category} …")
                val (n, sk) = writeTree(root, zos, skipped)
                entries += n
                if (n > 0 || sk > 0) categories += CategoryStat(root.category, n, sk)
            }

            val manifest = buildManifest(
                context = context,
                createdAt = createdAt,
                encrypted = encrypted,
                categories = categories,
                skipped = skipped,
                maskedKeys = maskedKeys,
                roots = roots,
            )
            zos.putNextEntry(ZipEntry(MANIFEST_NAME))
            zos.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        if (encrypted) {
            onProgress("加密备份包…")
            // 整包读入 + Base64 字符串 + 密文 ≈ 3 倍内存，超过闸门直接失败并保留中间包以便用户改用不加密备份。
            val sizeLimit = 200L * 1024 * 1024
            if (zipFile.length() > sizeLimit) {
                throw IllegalStateException(
                    "备份内容 ${zipFile.length() / 1048576}MB，超过加密上限 ${sizeLimit / 1048576}MB，" +
                        "加密需要约 3 倍内存。请关闭密码改用不加密备份（中间包保留在 ${zipFile.name}）。"
                )
            }
            val plain = zipFile.readBytes()
            val payload = Base64.encodeToString(plain, Base64.NO_WRAP)
            plain.fill(0)
            val blob = BackupCrypto.encrypt(payload, password!!)
            val encTmp = File(dir, ".enc-" + tag + ".tmp")
            FileOutputStream(encTmp).use { it.write(blob) }
            if (!encTmp.renameTo(outFile)) {
                encTmp.copyTo(outFile, overwrite = true)
                encTmp.delete()
            }
            zipFile.delete()
        } else {
            if (!zipFile.renameTo(outFile)) {
                zipFile.copyTo(outFile, overwrite = true)
                zipFile.delete()
            }
        }

        onProgress("备份完成")
        return Summary(
            path = outFile.absolutePath,
            bytes = outFile.length(),
            entries = entries,
            skipped = skipped.toList(),
            encrypted = encrypted,
            categories = categories.toList(),
        )
    }

    private fun writePrefs(
        context: Context,
        zos: ZipOutputStream,
        skipped: MutableList<String>,
        maskedKeys: MutableList<String>,
    ): CategoryStat {
        val prefsDir = File(context.applicationInfo.dataDir, "prefs")
        var entries = 0
        var sk = 0
        val xmls = prefsDir.listFiles { f -> f.isFile && f.name.endsWith(".xml") } ?: return CategoryStat("prefs", 0, 0)
        for (xml in xmls) {
            val name = xml.name.removeSuffix(".xml")
            val all = runCatching { context.getSharedPreferences(name, Context.MODE_PRIVATE).all }.getOrNull() ?: continue
            val out = JSONObject()
            var kept = 0
            for ((k, v) in all) {
                if (isSensitiveKey(k)) {
                    sk++
                    maskedKeys += "prefs/$name/$k"
                    skipped += "prefs/$name/$k (敏感键，已脱敏)"
                    continue
                }
                out.put(k, encodeValue(v))
                kept++
            }
            if (kept == 0) continue
            zos.putNextEntry(ZipEntry("prefs/$name.json"))
            zos.write(out.toString().toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            entries++
        }
        return CategoryStat("prefs", entries, sk)
    }

    private fun writeTree(
        root: Root,
        zos: ZipOutputStream,
        skipped: MutableList<String>,
    ): Pair<Int, Int> {
        val base = root.dir ?: return 0 to 0
        val basePath = base.absolutePath
        var entries = 0
        var sk = 0
        val stack = ArrayDeque<File>()
        stack.addLast(base)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (f in children) {
                if (f.isDirectory) {
                    stack.addLast(f)
                    continue
                }
                if (!f.isFile) continue
                val rel = f.absolutePath.removePrefix(basePath)
                    .trimStart(File.separatorChar)
                    .replace(File.separatorChar, '/')
                if (rel.isEmpty()) continue
                if (f.length() > MAX_SINGLE_FILE_BYTES) {
                    sk++
                    skipped += root.prefix + rel + " (" + formatBytes(f.length()) + "，超过 20 MB 上限)"
                    continue
                }
                if (entries >= MAX_ENTRIES) {
                    sk++
                    skipped += root.prefix + rel + " (条目数达到上限)"
                    continue
                }
                try {
                    zos.putNextEntry(ZipEntry(root.prefix + rel))
                    f.inputStream().use { it.copyTo(zos, COPY_BUFFER) }
                    zos.closeEntry()
                    entries++
                } catch (e: Exception) {
                    runCatching { zos.closeEntry() }
                    sk++
                    skipped += root.prefix + rel + " (读取失败: " + e.javaClass.simpleName + ")"
                }
            }
        }
        return entries to sk
    }

    private fun buildManifest(
        context: Context,
        createdAt: Long,
        encrypted: Boolean,
        categories: List<CategoryStat>,
        skipped: List<String>,
        maskedKeys: List<String>,
        roots: List<Root>,
    ): JSONObject {
        val total = categories.sumOf { it.entries }
        val catArr = JSONArray()
        categories.forEach { c ->
            catArr.put(
                JSONObject()
                    .put("name", c.name)
                    .put("entries", c.entries)
                    .put("skipped", c.skipped),
            )
        }
        val rootArr = JSONArray()
        roots.forEach { r ->
            if (r.dir == null || !r.dir.isDirectory) return@forEach
            rootArr.put(
                JSONObject()
                    .put("prefix", r.prefix)
                    .put("target", r.target)
                    .put("category", r.category),
            )
        }
        return JSONObject()
            .put("format", FORMAT_VERSION)
            .put("generator", "TaffyNiHe/FullBackup")
            .put("createdAt", createdAt)
            .put("createdAtIso", isoTime(createdAt))
            .put("appVersion", appVersion(context))
            .put("packageName", context.packageName)
            .put("encrypted", encrypted)
            .put("totalEntries", total)
            .put("totalSkipped", skipped.size)
            .put("categories", catArr)
            .put("skipped", JSONArray(skipped))
            .put("maskedKeys", JSONArray(maskedKeys))
            .put("roots", rootArr)
    }

    // ---------- 恢复 ----------

    private class RestoreResult(
        val entries: Int,
        val skipped: MutableList<String>,
        val categories: List<CategoryStat>,
    )

    private fun restoreFrom(
        context: Context,
        zip: File,
        roots: List<Root>,
        onProgress: (String) -> Unit,
    ): RestoreResult {
        // 两遍流式读取：第一遍只恢复 prefs，第二遍恢复 tasks/extensions/snapshots/workspaces，
        // 保证恢复顺序固定为 偏好设置 -> 任务/扩展/快照 -> 工作区，且不需要把 ZIP 读进内存
        onProgress("恢复偏好设置…")
        val prefsPass = restorePass(context, zip, roots, true)
        onProgress("恢复任务/扩展/快照与工作区…")
        val dataPass = restorePass(context, zip, roots, false)
        onProgress("恢复完成")
        return merge(prefsPass, dataPass)
    }

    private fun merge(a: RestoreResult, b: RestoreResult): RestoreResult {
        val counts = LinkedHashMap<String, IntArray>()
        fun add(cats: List<CategoryStat>) {
            for (c in cats) {
                val arr = counts.getOrPut(c.name) { intArrayOf(0, 0) }
                arr[0] += c.entries
                arr[1] += c.skipped
            }
        }
        add(a.categories)
        add(b.categories)
        val skipped = (a.skipped + b.skipped).toMutableList()
        return RestoreResult(a.entries + b.entries, skipped, counts.map { (k, v) -> CategoryStat(k, v[0], v[1]) })
    }

    /** 恢复前把受影响的目录挪到一边，返回 (原路径, 备份路径)。 */
    private fun stageTargetDirs(context: Context, roots: List<Root>): List<Pair<File, File>> {
        val staged = ArrayList<Pair<File, File>>()
        val stamp = System.currentTimeMillis()
        for (root in roots) {
            val base = runCatching { resolveTarget(context, root.target) }.getOrNull() ?: continue
            if (!base.isDirectory) continue
            if (staged.any { it.first.absolutePath == base.absolutePath }) continue
            val parent = base.parentFile ?: continue
            val old = File(parent, base.name + ".restore-old-" + stamp)
            if (runCatching { base.renameTo(old) }.getOrDefault(false)) staged += base to old
        }
        return staged
    }

    /** 取消/回滚后处理被挪走的旧目录：keepNew=false 时先清掉新写入的半成品再还原。 */
    private fun restoreStagedDirs(staged: List<Pair<File, File>>, keepNew: Boolean) {
        for ((orig, old) in staged) {
            if (!keepNew) runCatching { if (orig.exists()) orig.deleteRecursively() }
            if (!orig.exists()) runCatching { old.renameTo(orig) }
            runCatching { if (old.exists()) old.deleteRecursively() }
        }
    }

    private fun restorePass(
        context: Context,
        zip: File,
        roots: List<Root>,
        prefsOnly: Boolean,
    ): RestoreResult {
        val counts = LinkedHashMap<String, IntArray>()
        val skipped = mutableListOf<String>()
        var total = 0
        var seen = 0

        fun bump(category: String, ok: Boolean) {
            val arr = counts.getOrPut(category) { intArrayOf(0, 0) }
            if (ok) arr[0]++ else arr[1]++
        }

        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val name = e.name ?: ""
                if (e.isDirectory || name == MANIFEST_NAME) {
                    zis.closeEntry()
                    e = zis.nextEntry
                    continue
                }
                val isPrefs = name.startsWith("prefs/") && name.endsWith(".json")
                if (isPrefs != prefsOnly) {
                    zis.closeEntry()
                    e = zis.nextEntry
                    continue
                }
                if (++seen > MAX_ENTRIES) {
                    skipped += name + " (超出条目数上限，未处理)"
                    bump("other", false)
                    zis.closeEntry()
                    e = zis.nextEntry
                    continue
                }

                if (isPrefs) {
                    val prefsName = name.removePrefix("prefs/").removeSuffix(".json")
                    if (prefsName.isBlank() || prefsName.contains('/')) {
                        skipped += name + " (非法的偏好文件名)"
                        bump("prefs", false)
                    } else {
                        val raw = ByteArrayOutputStream()
                        zis.copyTo(raw, COPY_BUFFER)
                        val stat = applyPrefs(context, prefsName, raw.toByteArray(), skipped)
                        if (stat.first) {
                            total++
                            bump("prefs", true)
                        } else {
                            bump("prefs", false)
                        }
                        if (stat.second > 0) {
                            val arr = counts.getOrPut("prefs") { intArrayOf(0, 0) }
                            arr[1] += stat.second
                        }
                    }
                    zis.closeEntry()
                    e = zis.nextEntry
                    continue
                }

                val root = roots
                    .filter { it.prefix.isNotEmpty() && name.startsWith(it.prefix) }
                    .maxByOrNull { it.prefix.length }
                if (root == null) {
                    skipped += name + " (未知条目，已忽略)"
                    bump("other", false)
                    zis.closeEntry()
                    e = zis.nextEntry
                    continue
                }
                val base = resolveTarget(context, root.target)
                val rel = name.removePrefix(root.prefix)
                val dest = if (base == null) null else safeResolve(base, rel)
                if (dest == null) {
                    skipped += name + " (非法路径，已拒绝)"
                    bump(root.category, false)
                    zis.closeEntry()
                    e = zis.nextEntry
                    continue
                }
                try {
                    dest.parentFile?.mkdirs()
                    // 逐条目先写临时文件再原子替换：恢复中断（进程被杀/磁盘满）时
                    // 不会把用户原有文件截成半截。
                    val tmp = File.createTempFile("restore_", ".tmp", dest.parentFile)
                    try {
                        // 上限按 zip 条目声明大小放宽 1 倍：畸形备份包（解压炸弹）不能写满磁盘，
                        // 而回滚本身也需要空间。
                        val ceiling = if (e.size > 0) e.size * 2 + 1_000_000L else 512L * 1024 * 1024
                        FileOutputStream(tmp).use { out ->
                            val buf = ByteArray(COPY_BUFFER)
                            var written = 0L
                            while (true) {
                                val n = zis.read(buf)
                                if (n < 0) break
                                written += n
                                if (written > ceiling) error("entry too large (possible zip bomb): $name")
                                out.write(buf, 0, n)
                            }
                        }
                        ZipRepack.replaceAtomically(tmp, dest)
                    } finally {
                        runCatching { tmp.delete() }
                    }
                    total++
                    bump(root.category, true)
                } catch (err: Exception) {
                    skipped += name + " (写入失败: " + err.javaClass.simpleName + ")"
                    bump(root.category, false)
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }

        return RestoreResult(total, skipped, counts.map { (k, v) -> CategoryStat(k, v[0], v[1]) })
    }

    private fun applyPrefs(
        context: Context,
        name: String,
        raw: ByteArray,
        skipped: MutableList<String>,
    ): Pair<Boolean, Int> {
        val obj = runCatching { JSONObject(raw.decodeToString()) }.getOrNull() ?: return false to 0
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        var restored = 0
        var sk = 0
        for (k in obj.keys()) {
            if (isSensitiveKey(k)) {
                sk++
                skipped += "prefs/$name/$k (敏感键，恢复时跳过)"
                continue
            }
            val item = obj.optJSONObject(k) ?: continue
            when (item.optString("t")) {
                "b" -> editor.putBoolean(k, item.optBoolean("v"))
                "i" -> editor.putInt(k, item.optInt("v"))
                "l" -> editor.putLong(k, item.optLong("v"))
                "f" -> editor.putFloat(k, item.optDouble("v").toFloat())
                "ss" -> {
                    val arr = item.optJSONArray("v")
                    val set = LinkedHashSet<String>()
                    if (arr != null) for (i in 0 until arr.length()) set += arr.optString(i)
                    editor.putStringSet(k, set)
                }
                else -> editor.putString(k, item.optString("v"))
            }
            restored++
        }
        // commit 失败（磁盘满）时不能算恢复成功
        val committed = editor.commit()
        return (restored > 0 && committed) to sk
    }

    // ---------- 目录 / 工具 ----------

    private fun roots(context: Context): List<Root> {
        val files = context.filesDir
        val cache = context.cacheDir
        val list = mutableListOf<Root>()
        list += Root("tasks/", "files:tasks", "tasks", File(files, "tasks"))
        list += Root("extensions/", "files:extensions", "extensions", File(files, "extensions"))
        list += Root("snapshots/", "files:edit-snapshots", "snapshots", File(files, "edit-snapshots"))
        list += Root("workspaces/fs/", "files:workspaces", "workspaces", File(files, "workspaces"))
        list += Root("workspaces/fs-alt/", "files:workspace", "workspaces", File(files, "workspace"))
        list += Root("workspaces/cache-ws/", "cache:workspaces", "workspaces", File(cache, "workspaces"))
        val picked = cache.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("picked_") }
            ?.sortedBy { it.name }
            ?: emptyList()
        for (d in picked) {
            list += Root("workspaces/cache-picked/" + d.name + "/", "cache:" + d.name, "workspaces", d)
        }
        return list
    }

    private fun parseRoots(manifest: JSONObject): List<Root> {
        val arr = manifest.optJSONArray("roots") ?: return emptyList()
        val out = mutableListOf<Root>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val prefix = o.optString("prefix")
            val target = o.optString("target")
            val category = o.optString("category").ifBlank { "other" }
            if (prefix.isBlank() || prefix.contains("..") || prefix.contains('\\')) continue
            if (target.isBlank()) continue
            out += Root(prefix, target, category, null)
        }
        return out
    }

    private fun resolveTarget(context: Context, target: String): File? {
        val idx = target.indexOf(':')
        if (idx <= 0) return null
        val kind = target.substring(0, idx)
        val name = target.substring(idx + 1)
        if (name.isBlank() || name.contains('/') || name.contains('\\') || name.contains("..")) return null
        return when (kind) {
            "files" -> File(context.filesDir, name)
            "cache" -> File(context.cacheDir, name)
            else -> null
        }
    }

    // 防 ZipSlip：拒绝绝对路径与 ..
    private fun safeResolve(base: File, rel: String): File? {
        val cleaned = rel.replace('\\', '/')
        if (cleaned.isBlank() || cleaned.startsWith("/")) return null
        if (cleaned.split('/').any { it.isEmpty() || it == ".." || it == "." }) return null
        return try {
            val basePath = base.canonicalPath + File.separator
            val f = File(base, cleaned)
            if (f.canonicalPath.startsWith(basePath)) f else null
        } catch (e: Exception) {
            null
        }
    }

    private fun backupsDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "backups")
    }

    private fun readManifest(zip: File): JSONObject? {
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (e.name == MANIFEST_NAME) {
                    val buf = ByteArrayOutputStream()
                    zis.copyTo(buf, COPY_BUFFER)
                    return runCatching { JSONObject(buf.toByteArray().decodeToString()) }.getOrNull()
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        return null
    }

    private fun isEncryptedFile(file: File): Boolean {
        val head = ByteArray(8)
        val read = FileInputStream(file).use { it.read(head) }
        return read == head.size && BackupCrypto.isEncrypted(head)
    }

    private fun encodeValue(v: Any?): JSONObject = when (v) {
        is Boolean -> JSONObject().put("t", "b").put("v", v)
        is Int -> JSONObject().put("t", "i").put("v", v)
        is Long -> JSONObject().put("t", "l").put("v", v)
        is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
        is String -> JSONObject().put("t", "s").put("v", v)
        is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(v.mapNotNull { it?.toString() }))
        else -> JSONObject().put("t", "s").put("v", v?.toString().orEmpty())
    }

    private fun appVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown")

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private fun isoTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date(millis))

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
        bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
