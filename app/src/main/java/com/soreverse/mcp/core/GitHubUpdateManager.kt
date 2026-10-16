package com.soreverse.mcp.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.soreverse.mcp.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

data class GitHubRelease(
    val tag: String,
    val name: String,
    val notes: String,
    val pageUrl: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    val checksumUrl: String?,
)

sealed interface UpdateCheckResult {
    data class Available(val release: GitHubRelease) : UpdateCheckResult
    data object Current : UpdateCheckResult
}

sealed interface UpdateDownloadEvent {
    data class Probing(val total: Int) : UpdateDownloadEvent
    data class ProbeResult(
        val source: String,
        val reachable: Boolean,
        val latencyMs: Long,
        val completed: Int,
        val total: Int,
    ) : UpdateDownloadEvent
    data class Selected(val source: String) : UpdateDownloadEvent
    data class Downloading(val source: String, val percent: Int) : UpdateDownloadEvent
    data object Verifying : UpdateDownloadEvent
    // Emitted when checksum verification is skipped (asset missing/unreachable).
    data class VerifySkipped(val reason: String) : UpdateDownloadEvent
}

// The checksum asset could not be fetched; verification is skipped (soft).
class ChecksumUnavailableException(message: String) : Exception(message)

// A checksum was fetched but did not match the download; hard failure.
class ChecksumMismatchException(message: String) : Exception(message)

class GitHubUpdateManager(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val probeClient = client.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // Serializes the whole download() body. When the user switches mirrors we
    // cancel the previous download coroutine, but cancellation is cooperative and
    // the old coroutine may still be mid-write when the new one starts. Without
    // this lock two coroutines could race on the same .part file, and a
    // late-finishing old mirror could overwrite/verify on top of the new one
    // (exactly the "mirror A finishes then B downloads again" bug). Holding the
    // mutex for the entire download makes a new attempt wait until the previous
    // one has fully unwound.
    private val downloadMutex = Mutex()

    suspend fun fetchLatestRelease(): Result<GitHubRelease> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(LATEST_RELEASE_URL)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "SOMCP/${BuildConfig.VERSION_NAME}")
                .build()
            val result = client.newCall(request).await().use { response ->
                if (response.code == 404) error("GitHub release not found (404)")
                if (!response.isSuccessful) error("GitHub HTTP ${response.code} ${response.message}")
                val root = JSONObject(response.body.string())
                val tag = root.optString("tag_name")
                val assets = root.optJSONArray("assets") ?: error("Release has no assets")
                val apk = selectApk((0 until assets.length()).map { assets.getJSONObject(it) })
                    ?: error("Release has no APK for ${Build.SUPPORTED_ABIS.joinToString()}")
                val checksum = (0 until assets.length())
                    .map { assets.getJSONObject(it) }
                    .firstOrNull {
                        val name = it.optString("name")
                        name == "${apk.optString("name")}.sha256" || name == "SHA256SUMS"
                    }
                GitHubRelease(
                    tag = tag,
                    name = root.optString("name").ifBlank { tag },
                    notes = root.optString("body"),
                    pageUrl = root.optString("html_url"),
                    apkName = apk.getString("name"),
                    apkUrl = apk.getString("browser_download_url"),
                    apkSize = apk.optLong("size"),
                    checksumUrl = checksum?.optString("browser_download_url")?.takeIf(String::isNotBlank),
                )
            }
            Result.success(result)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    suspend fun check(channel: UpdateChannel = UpdateChannel.STABLE): Result<UpdateCheckResult> = withContext(Dispatchers.IO) {
        // 上游 1.0.21 借鉴: 更新频道（正式版 / 测试版）。检查目标为塔菲逆核自己的仓库
        // (hUh63/TaffyNiHe)，不再指向原版作者仓库，避免被官方 release 覆盖。
        try {
            val result = when (channel) {
                UpdateChannel.STABLE -> checkStable()
                UpdateChannel.BETA -> checkBeta()
            }
            Result.success(result)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    // 正式版通道 (上游 1.0.22 借鉴): 不再信任 /releases/latest 指针, 改为枚举 releases 列表后
    // 自行挑选「非 draft、非 prerelease 里版本号最高」的一条。原因: /releases/latest 会漏掉
    // 「同版本号重新发布」的构建, 且当仓库当时没有正式版时该指针可能指向错误对象。
    private suspend fun checkStable(): UpdateCheckResult {
        val request = Request.Builder()
            .url("$RELEASES_URL?per_page=30")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "TaffyNiHe/${BuildConfig.VERSION_NAME}")
            .build()
        return client.newCall(request).await().use { response ->
            if (response.code == 404) return@use UpdateCheckResult.Current
            if (!response.isSuccessful) error("GitHub HTTP ${response.code} ${response.message}")
            val array = JSONArray(response.body.string())
            // 版本号按语义化逐段数字比较 (compareVersions), 不能字符串比较, 否则 1.3.101 < 1.3.99。
            var highest: JSONObject? = null
            for (index in 0 until array.length()) {
                val candidate = array.getJSONObject(index)
                if (candidate.optBoolean("draft") || candidate.optBoolean("prerelease")) continue
                val seen = highest
                if (seen == null || compareVersions(candidate.optString("tag_name"), seen.optString("tag_name")) > 0) {
                    highest = candidate
                }
            }
            val stable = highest ?: return@use UpdateCheckResult.Current
            buildRelease(stable, required = true)
        }
    }

    // Beta 频道：取最近 30 个 release 中第一个 prerelease（非 draft）。
    private suspend fun checkBeta(): UpdateCheckResult {
        val request = Request.Builder()
            .url("$RELEASES_URL?per_page=30")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "TaffyNiHe/${BuildConfig.VERSION_NAME}")
            .build()
        return client.newCall(request).await().use { response ->
            if (!response.isSuccessful) error("GitHub HTTP ${response.code} ${response.message}")
            val array = JSONArray(response.body.string())
            val candidate = (0 until array.length())
                .map { array.getJSONObject(it) }
                .firstOrNull { it.optBoolean("prerelease") && !it.optBoolean("draft") }
            candidate?.let { buildRelease(it, required = false) } ?: UpdateCheckResult.Current
        }
    }

    // 两通道综合检查: 并发拉取 STABLE 与 BETA, 返回版本号更高的那条作为提醒目标。
    // 新增入口, 不改变既有 check(channel) 的调用点与签名。
    suspend fun checkLatest(): Result<UpdateCheckResult> = withContext(Dispatchers.IO) {
        try {
            val results = coroutineScope {
                val stableDeferred = async { checkStable() }
                val betaDeferred = async { checkBeta() }
                listOf(stableDeferred.await(), betaDeferred.await())
            }
            val stableRelease = (results.getOrNull(0) as? UpdateCheckResult.Available)?.release
            val betaRelease = (results.getOrNull(1) as? UpdateCheckResult.Available)?.release
            val winner = when {
                stableRelease == null -> betaRelease
                betaRelease == null -> stableRelease
                compareVersions(betaRelease.tag, stableRelease.tag) > 0 -> betaRelease
                else -> stableRelease
            }
            Result.success(winner?.let { UpdateCheckResult.Available(it) } ?: UpdateCheckResult.Current)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    // 把一条 release 的 JSON 组装成检查结果。required=true 时缺资源视为硬错误(正式版),
    // required=false 时缺资源降级为 Current(测试版)。同时处理「同版本号重新发布」:
    // 版本号与本地相同时, 若发布时间晚于本地记录的「已忽略/已提醒」时间戳, 仍提示可重新获取。
    private fun buildRelease(root: JSONObject, required: Boolean): UpdateCheckResult {
        val tag = root.optString("tag_name")
        val publishedAt = root.optString("published_at")
        val newer = isNewer(tag, BuildConfig.VERSION_NAME)
        val sameVersion = !newer && compareVersions(tag, BuildConfig.VERSION_NAME) == 0
        val republished = sameVersion && isRepublishedSinceIgnored(tag, publishedAt)
        if (!newer && !republished) return UpdateCheckResult.Current
        val assets = root.optJSONArray("assets")
        val apk = assets?.let { selectApk((0 until it.length()).map { index -> it.getJSONObject(index) }) }
            ?: return if (required) error("Release has no APK for ${Build.SUPPORTED_ABIS.joinToString()}") else UpdateCheckResult.Current
        val checksum = (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .firstOrNull {
                val name = it.optString("name")
                name == "${apk.optString("name")}.sha256" || name == "SHA256SUMS"
            }
        // 记下本次提醒(或用户忽略)所依据的发布时间, 供「同版本重新发布」二次提醒判断。
        recordReminder(tag, publishedAt)
        return UpdateCheckResult.Available(
            GitHubRelease(
                tag = tag,
                name = root.optString("name").ifBlank { tag },
                notes = root.optString("body"),
                pageUrl = root.optString("html_url"),
                apkName = apk.getString("name"),
                apkUrl = apk.getString("browser_download_url"),
                apkSize = apk.optLong("size"),
                checksumUrl = checksum?.optString("browser_download_url")?.takeIf(String::isNotBlank),
            ),
        )
    }

    suspend fun download(
        release: GitHubRelease,
        forcedSource: String? = null,
        onEvent: (UpdateDownloadEvent) -> Unit,
    ): Result<File> =
        withContext(Dispatchers.IO) {
            try {
                downloadMutex.withLock {
                suspend fun emit(event: UpdateDownloadEvent) {
                    withContext(Dishetchers.Main.immediate) { onEvent(event) }
                }
                // A previous (now-cancelled) attempt may have just completed and
                // populated the cache; re-check inside the lock so a mirror switch
                // that raced a finishing download reuses the verified file instead
                // of downloading again.
                cachedDownload(release)?.let { return@withLock Result.success(it) }
                val directory = File(context.cacheDir, "updates").apply { mkdirs() }
                val target = File(directory, release.apkName.substringAfterLast('/'))
                val partial = File(directory, "${target.name}.part")
                val verified = File(directory, "${target.name}.verified")
                directory.listFiles()?.filter { it !in setOf(target, verified) }?.forEach { it.delete() }
                // When the user manually picks a mirror we honour it first and
                // skip the speed probe entirely (their choice solves the
                // "fast to probe but slow to download" case); the remaining
                // mirrors stay as automatic fallbacks after it.
                val candidates = if (forcedSource != null) {
                    val all = DownloadMirrorPolicy.candidates(release.apkUrl)
                    val preferred = all.filter { sourceName(it) == forcedSource }
                    (preferred + all.filterNot { it in preferred }).ifEmpty { all }
                } else {
                    rankedDownloadUrls(release.apkUrl, ::emit)
                }
                var lastFailure: Throwable? = null
                var downloaded = false
                for (url in candidates) {
                    ensureActive()
                    partial.delete()
                    try {
                        emit(UpdateDownloadEvent.Selected(sourceName(url)))
                        val request = Request.Builder()
                            .url(url)
                            .header("User-Agent", "SOMCP/${BuildConfig.VERSION_NAME}")
                            .build()
                        client.newCall(request).await().use { response ->
                            if (!response.isSuccessful) error("Download HTTP ${response.code} ${response.message}")
                            val body = response.body
                            val total = body.contentLength().takeIf { it > 0 } ?: release.apkSize
                            body.byteStream().use { input ->
                                partial.outputStream().use { output ->
                                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                    var copied = 0L
                                    var lastPercent = -1
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        output.write(buffer, 0, count)
                                        copied += count
                                        if (total > 0) {
                                            val percent = ((copied * 100) / total).toInt().coerceIn(0, 100)
                                            if (percent != lastPercent) {
                                                lastPercent = percent
                                                emit(UpdateDownloadEvent.Downloading(sourceName(url), percent))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (partial.length() > 4 && partial.inputStream().use {
                                val header = ByteArray(4)
                                it.read(header) == 4 && header.contentEquals(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
                            }) {
                            downloaded = true
                            break
                        }
                        error("Downloaded asset is not a valid APK archive")
                    } catch (error: CancellationException) {
                        partial.delete()
                        throw error
                    } catch (error: Throwable) {
                        lastFailure = error
                    }
                }
                require(downloaded) { lastFailure?.message ?: "All download mirrors failed" }
                emit(UpdateDownloadEvent.Verifying)
                // 上游 1.0.22 (#131) 借鉴: 只要 release 发布了 checksum 资产，校验就是强制
                // 的 —— 取不到或对不上都中止安装。旧的「取不到就降级为未校验安装」会让
                // 控制任一镜像的攻击者只需丢掉 checksum 请求就能强制一次未校验安装。
                // 只有完全没有 checksum 资产的 release 才允许未校验安装（且不写 .verified
                // 标记，一旦出现 checksum 会重新校验）。
                val verifiedHash = release.checksumUrl?.let { url ->
                    runCatching { verifyChecksum(partial, url, target.name) }
                        .getOrElse { failure ->
                            partial.delete()
                            throw failure
                        }
                } ?: run {
                    emit(UpdateDownloadEvent.VerifySkipped("no checksum published"))
                    null
                }
                runCatching {
                    Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                }.getOrElse {
                    Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                // Only persist the .verified marker when we actually verified the
                // hash; otherwise the cached-download fast path must re-check.
                if (verifiedHash != null) verified.writeText(verifiedHash) else verified.delete()
                Result.success(target)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                File(File(context.cacheDir, "updates"), "${release.apkName.substringAfterLast('/')}.part").delete()
                Result.failure(error)
            }
        }

    fun cachedDownload(release: GitHubRelease): File? {
        val file = File(File(context.cacheDir, "updates"), release.apkName.substringAfterLast('/'))
        val verified = File(file.parentFile, "${file.name}.verified")
        if (!file.isFile || file.length() <= 4) return null
        val sizeOk = release.apkSize <= 0 || file.length() == release.apkSize
        if (!sizeOk) return null
        val expectedHash = verified.readTextOrNull()?.trim()?.lowercase()?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
        if (expectedHash != null) {
            // Re-hash the actual bytes: a predictable marker alone must never be trusted.
            val actualHash = runCatching { fileSha256(file) }.getOrNull() ?: return null
            return file.takeIf { actualHash == expectedHash }
        }
        // 上游 1.0.22 (#131) 借鉴: 没有 .verified 标记说明这份文件从未被哈希校验过。
        // 只有当 release 完全没有 checksum 资产时才允许复用；一旦存在 checksum，就必须
        // 重新下载，让强制校验跑在真实字节上，而不是相信 ZIP magic 这种结构性检查。
        if (release.checksumUrl != null) return null
        val looksLikeApk = runCatching {
            file.inputStream().use {
                val header = ByteArray(4)
                it.read(header) == 4 && header.contentEquals(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
            }
        }.getOrDefault(false)
        return file.takeIf { looksLikeApk }
    }

    private suspend fun rankedDownloadUrls(
        original: String,
        emit: suspend (UpdateDownloadEvent) -> Unit,
    ): List<String> = coroutineScope {
        val candidates = DownloadMirrorPolicy.candidates(original)
        emit(UpdateDownloadEvent.Probing(candidates.size))
        val completed = java.util.concurrent.atomic.AtomicInteger(0)
        val ranked = candidates.map { url -> async(Dispatchers.IO) {
                ensureActive()
                val started = System.nanoTime()
                val reachable = try {
                    probeClient.newCall(
                        Request.Builder()
                            .head()
                            .url(url)
                            .header("User-Agent", "SOMCP/${BuildConfig.VERSION_NAME}")
                            .build(),
                    ).await().use { it.isSuccessful || it.code in 300..399 || it.code == 405 }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    false
                }
                val latencyMs = (System.nanoTime() - started) / 1_000_000
                emit(
                    UpdateDownloadEvent.ProbeResult(
                        sourceName(url),
                        reachable,
                        latencyMs,
                        completed.incrementAndGet(),
                        candidates.size,
                    ),
                )
                Triple(url, reachable, latencyMs)
            }
        }.awaitAll()
        ranked.sortedWith(
            compareByDescending<Triple<String, Boolean, Long>> { it.second }.thenBy { it.third },
        ).map { it.first }
    }

    private fun sourceName(url: String): String = runCatching { Uri.parse(url).host.orEmpty() }
        .getOrDefault(url.substringBefore('/'))

    // Distinct, user-selectable mirror host names for [release], in policy order.
    fun mirrorSources(release: GitHubRelease): List<String> =
        DownloadMirrorPolicy.candidates(release.apkUrl).map(::sourceName).filter { it.isNotBlank() }.distinct()


    fun install(file: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        return true
    }

    private fun selectApk(assets: List<JSONObject>): JSONObject? {
        val apks = assets.filter { it.optString("name").endsWith(".apk", true) }
        val abiNames = Build.SUPPORTED_ABIS.flatMap { abi ->
            listOf(abi.lowercase(), abi.lowercase().replace('-', '_'))
        }
        return apks.firstOrNull { asset -> abiNames.any { it in asset.optString("name").lowercase() } }
            ?: apks.firstOrNull { "universal" in it.optString("name").lowercase() }
            ?: apks.singleOrNull()
    }

    // 语义化版本比较: 逐段按数字比较, 缺失段视为 0。a>b 返回 1, a<b 返回 -1, 相等返回 0。
    // 例: compareVersions("1.3.101", "1.3.99") == 1 —— 逐段数字比较, 不会像字符串比较那样把 "101" 排在 "99" 前。
    private fun compareVersions(a: String, b: String): Int {
        val left = parseVersion(a)
        val right = parseVersion(b)
        for (index in 0 until maxOf(left.size, right.size)) {
            val comparison = (left.getOrNull(index) ?: 0).compareTo(right.getOrNull(index) ?: 0)
            if (comparison != 0) return if (comparison > 0) 1 else -1
        }
        return 0
    }

    // 解析版本号: 去掉前缀 v, 以 . - + 切段后只保留数字段(忽略 alpha/beta/rc 等标签文字)。
    private fun parseVersion(raw: String): List<Int> =
        raw.trim().removePrefix("v").split('.', '-', '+').mapNotNull(String::toIntOrNull)

    private fun isNewer(remote: String, local: String): Boolean = compareVersions(remote, local) > 0

    // 「已忽略/已提醒」记录: 按版本号保存时间戳(毫秒), 落到 SharedPreferences 以便重启后仍生效。
    private val ignoredPrefs by lazy {
        context.getSharedPreferences("update_ignored_versions", Context.MODE_PRIVATE)
    }

    private fun ignoreKey(tag: String): String = "v_" + tag.trim().removePrefix("v").removePrefix("V")

    private fun ignoredPublishedAt(tag: String): Long? {
        val key = ignoreKey(tag)
        if (!ignoredPrefs.contains(key)) return null
        return ignoredPrefs.getLong(key, 0L).takeIf { it > 0L }
    }

    private fun recordReminder(tag: String, publishedAt: String?) {
        val stamp = parseGitHubInstant(publishedAt) ?: System.currentTimeMillis()
        ignoredPrefs.edit().putLong(ignoreKey(tag), stamp).apply()
    }

    // 供 UI 在用户选择「忽略此版本」时调用: 记录当前时间, 之后除非仓库重新发布更晚的构建, 否则不再提醒该版本。
    fun ignoreVersion(tag: String) {
        if (tag.isBlank()) return
        ignoredPrefs.edit().putLong(ignoreKey(tag), System.currentTimeMillis()).apply()
    }

    // 同版本号重新发布判定: 本地已有该版本的「已忽略/已提醒」记录, 且其发布时间晚于该记录时, 视为需要重新提醒。
    private fun isRepublishedSinceIgnored(tag: String, publishedAt: String?): Boolean {
        val recorded = ignoredPublishedAt(tag) ?: return false
        val published = parseGitHubInstant(publishedAt) ?: return false
        return published > recorded
    }

    // 把 GitHub 的 published_at (ISO-8601 UTC, 形如 2023-05-15T07:57:04Z) 解析成 epoch 毫秒。
    private fun parseGitHubInstant(iso: String?): Long? {
        val raw = iso?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val normalized = raw.replace(Regex("\\.\\d+"), "")
        return runCatching { java.time.Instant.parse(normalized).toEpochMilli() }
            .recoverCatching { java.time.OffsetDateTime.parse(normalized).toInstant().toEpochMilli() }
            .getOrNull()
    }

    private suspend fun verifyChecksum(file: File, url: String, assetName: String = file.name): String {
        var expected: String? = null
        var lastFailure: Throwable? = null
        // 上游 1.0.22 (#131) 借鉴: 只走官方域候选（DownloadMirrorPolicy.checksumCandidates）
        // —— 绝不让校验和经由 APK 可能来自的那些第三方镜像下发。
        val candidates = DownloadMirrorPolicy.checksumCandidates(url)
        for (candidate in candidates) {
            try {
                val request = Request.Builder().url(candidate).header("User-Agent", "SOMCP/${BuildConfig.VERSION_NAME}").build()
                expected = client.newCall(request).await().use { response ->
                    if (!response.isSuccessful) error("Checksum HTTP ${response.code}")
                    val text = response.body.string()
                    Regex("(?i)([a-f0-9]{64})\\s+\\*?${Regex.escape(assetName)}(?:\\s|$)")
                        .find(text)?.groupValues?.get(1)
                        ?: Regex("(?i)^[a-f0-9]{64}$").find(text.trim())?.value
                        ?: error("Checksum file does not contain $assetName")
                }
                break
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                lastFailure = error
            }
        }
        // 取不到 checksum -> 硬失败，向调用方抛出并中止安装（不再有软降级）。
        val expectedHash = expected ?: throw ChecksumUnavailableException(lastFailure?.message ?: "Checksum fetch failed")
        val actual = fileSha256(file)
        // Obtained a checksum but it does not match -> hard failure (tampering).
        if (!actual.equals(expectedHash, true)) throw ChecksumMismatchException("APK SHA-256 verification failed")
        return actual.lowercase()
    }

    private fun fileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun File.readTextOrNull(): String? = runCatching { takeIf(File::isFile)?.readText() }.getOrNull()

    companion object {
        const val REPOSITORY_URL = "https://github.com/hUh63/TaffyNiHe"
        // 上游 1.0.22 起: 正式版通道不再信任 /releases/latest 指针, 改为枚举 RELEASES_URL
        // 自行挑选版本号最高的一条; 此常量仅保留给历史辅助方法 fetchLatestRelease() 使用。
        private const val LATEST_RELEASE_URL = "https://api.github.com/repos/hUh63/TaffyNiHe/releases/latest"
        private const val RELEASES_URL = "https://api.github.com/repos/hUh63/TaffyNiHe/releases"
        private const val CHECKSUM_MIRROR_ATTEMPTS = 4
    }
}

// 更新频道：正式版（stable）/ 测试版（beta，即 prerelease）。上游 1.0.21 借鉴。
enum class UpdateChannel { STABLE, BETA }
