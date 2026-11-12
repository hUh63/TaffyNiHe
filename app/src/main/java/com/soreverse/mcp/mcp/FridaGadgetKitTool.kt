package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Frida Gadget 版本库：管住"用哪个版本的 gadget"。
 *
 * 之前 taffy_apk_frida_gadget 只接受调用方自己传进来的 gadget 文件，
 * 版本从哪来、放哪里、有没有下对 ABI 全靠人肉。这个工具把它补齐成一条链：
 *
 *   versions（看有哪些版本）→ download（按 ABI 拉官方 .xz 并解压校验）
 *   → place（丢进 decode 目录的 lib/<abi>/）→ 交给 taffy_apk_frida_gadget 收尾
 *
 * 官方资产命名固定为 frida-gadget-<ver>-android-<arch>.so.xz，
 * arch 取 arm / arm64 / x86 / x86_64（注意和 ABI 名不同）。
 */
object FridaGadgetKitTool {

    private const val GADGET_NAME = "libfrida-gadget.so"
    private const val RELEASES_CACHE = "releases.json"
    private const val CACHE_TTL_MS = 60 * 60 * 1000L
    private const val MAX_DOWNLOAD_BYTES = 128L * 1024 * 1024

    /** ABI -> 官方资产里的架构名 / ELF e_machine。 */
    private val ABI_INFO: Map<String, Pair<String, Int>> = linkedMapOf(
        "arm64-v8a" to ("arm64" to 183),
        "armeabi-v7a" to ("arm" to 40),
        "x86" to ("x86" to 3),
        "x86_64" to ("x86_64" to 62),
    )

    val gadgetKit: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_frida_gadget_kit",
            "【Gadget 版本库】管理 frida-gadget 各版本/各 ABI 的本地库：action=status 看本地有什么；versions 列出官方可用版本；download 按 ABI 下载官方 .xz 并解压校验（自动识别 ELF 架构）；import 导入本地 .so/.xz；remove 删除；place 把库里的 gadget 放进 decode 目录 lib/<abi>/（之后用 taffy_apk_frida_gadget 配 config + 注入入口）。",
            "Manage local frida-gadget versions/ABIs: status/versions/download/import/remove/place (into decode dir lib/<abi>/).",
            "dynamic", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf("status (default) | versions | download | import | remove | place", "status", "versions", "download", "import", "remove", "place")
                "version" str "版本号，如 17.26.2；download/place 可写 latest 表示用缓存里的最新正式版"
                "abis" arr "download 用：要下载的 ABI 列表，默认 [\"arm64-v8a\"]"
                "abi" str "import/place/remove 用：单个 ABI（import 留空则按 ELF 自动识别）"
                "path" str "import 用：本地 .so 或 .xz 文件路径"
                "dir" str "place 用：目标 decode 目录（含 lib/ 子目录或将被创建）"
                "limit" int "versions 用：最多列多少个版本（默认 15）"
                "force" str "versions 用：true=忽略缓存重新拉取（默认 false）"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val action = args.str("action", "status").ifBlank { "status" }
            val root = File(ctx.context.filesDir, "frida-gadgets")
            return when (action) {
                "status" -> doStatus(root)
                "versions" -> doVersions(root, args.intValue("limit", 15).coerceIn(1, 50), args.str("force").toBoolean())
                "download" -> doDownload(root, args)
                "import" -> doImport(root, args)
                "remove" -> doRemove(root, args)
                "place" -> doPlace(root, args)
                else -> err("INVALID_ARGUMENT", "未知 action: $action", "action", action)
            }
        }
    }

    // ──────────────── status ────────────────

    private fun doStatus(root: File): JSONObject {
        val libs = JSONArray()
        var total = 0L
        val versions = root.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name } ?: emptyList()
        for (v in versions) {
            for (abi in ABI_INFO.keys) {
                val f = File(File(v, abi), GADGET_NAME)
                if (!f.isFile) continue
                total += f.length()
                libs.put(JSONObject()
                    .put("version", v.name)
                    .put("abi", abi)
                    .put("size", f.length())
                    .put("path", f.absolutePath))
            }
        }
        val cache = File(root, RELEASES_CACHE)
        return ok(JSONObject()
            .put("action", "status")
            .put("root", root.absolutePath)
            .put("libraries", libs)
            .put("count", libs.length())
            .put("totalBytes", total)
            .put("releasesCached", cache.isFile)
            .put("releasesCachedAt", if (cache.isFile) cache.lastModified() else 0)
            .put("hint", if (libs.length() == 0) "本地库是空的：versions 看可用版本，download 拉一个" else "place 把某个版本放进 decode 目录"))
    }

    // ──────────────── versions ────────────────

    private fun doVersions(root: File, limit: Int, force: Boolean): JSONObject {
        val cache = File(root, RELEASES_CACHE)
        val fresh = cache.isFile && !force && (System.currentTimeMillis() - cache.lastModified() < CACHE_TTL_MS)
        val raw: String? = if (fresh) {
            runCatching { cache.readText() }.getOrNull()
        } else {
            val fetched = fetchReleases()
            if (fetched == null) {
                // 拉取失败时退回旧缓存，总比什么都没有强
                runCatching { cache.readText() }.getOrNull()
            } else {
                root.mkdirs()
                runCatching { cache.writeText(fetched) }
                fetched
            }
        }
        val text = raw ?: return err("NETWORK", "无法访问 GitHub Releases（检查网络/代理），本地也没有缓存")

        val arr = runCatching { JSONArray(text) }.getOrElse { return err("PARSE", "版本缓存损坏，删掉 ${cache.absolutePath} 或 force=true 重试") }
        val out = JSONArray()
        var pre = 0
        var stable = 0
        for (i in 0 until arr.length()) {
            if (out.length() >= limit) break
            val r = arr.getJSONObject(i)
            val assets = r.optJSONArray("assets") ?: continue
            val abiUrls = linkedMapOf<String, JSONObject>()
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                val name = a.optString("name")
                val abi = abiOfAsset(name)
                if (abi != null) abiUrls[abi] = a
            }
            if (abiUrls.isEmpty()) continue
            val isPre = r.optBoolean("prerelease")
            if (isPre) pre++ else stable++
            out.put(JSONObject()
                .put("version", r.optString("tag_name"))
                .put("prerelease", isPre)
                .put("publishedAt", r.optString("published_at"))
                .put("abis", JSONArray().apply { for (k in abiUrls.keys) put(k) })
                .put("size", JSONObject().apply {
                    for ((k, a) in abiUrls) put(k, a.optLong("size"))
                }))
        }
        return ok(JSONObject()
            .put("action", "versions")
            .put("fromCache", fresh)
            .put("count", out.length())
            .put("stable", stable)
            .put("prerelease", pre)
            .put("versions", out)
            .put("next", "download version=<版本> abis=[\"arm64-v8a\"]"))
    }

    private fun abiOfAsset(assetName: String): String? {
        if (!assetName.startsWith("frida-gadget-") || !assetName.endsWith(".so.xz")) return null
        if (!assetName.contains("-android-")) return null
        val arch = assetName.substringAfter("-android-").removeSuffix(".so.xz")
        return ABI_INFO.entries.firstOrNull { it.value.first == arch }?.key
    }

    private fun fetchReleases(): String? {
        return runCatching {
            val url = URL("https://api.github.com/repos/frida/frida/releases?per_page=40")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 60000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "taffy-nihe")
            }
            try {
                if (conn.responseCode !in 200..299) return null
                conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    // ──────────────── download ────────────────

    private fun doDownload(root: File, args: JSONObject): JSONObject {
        val wantedAbis = runCatching {
            val a = args.optJSONArray("abis")
            if (a != null) (0 until a.length()).map { a.getString(it) } else {
                val single = args.str("abi")
                if (single.isNotBlank()) listOf(single) else listOf("arm64-v8a")
            }
        }.getOrDefault(listOf("arm64-v8a"))
        val abis = wantedAbis.map { normalizeAbi(it) }.filter { ABI_INFO.containsKey(it) }.distinct()
        if (abis.isEmpty()) return err("INVALID_ARGUMENT", "abis 至少要有一个合法值：${ABI_INFO.keys.joinToString()}", "abis", wantedAbis.joinToString())

        var version = args.str("version").ifBlank { "latest" }
        val releases = resolveReleases(root)
        if (version == "latest") {
            version = releases.firstOrNull { !it.isPre }?.version
                ?: return err("NO_RELEASE", "版本列表里没有正式版，显式指定 version 或先 versions 查看")
        }
        val rel = releases.firstOrNull { it.version == version }
            ?: return err("VERSION_NOT_FOUND", "版本 $version 不在最近的发布列表里，先 versions 确认", "version", version)

        val done = JSONArray()
        val failed = JSONArray()
        for (abi in abis) {
            val asset = rel.assets[abi]
            if (asset == null) {
                failed.put(JSONObject().put("abi", abi).put("reason", "该版本没有 $abi 资产"))
                continue
            }
            val target = File(File(File(root, version), abi), GADGET_NAME)
            if (target.isFile && target.length() > 0 && !args.str("force").toBoolean()) {
                done.put(JSONObject().put("abi", abi).put("path", target.absolutePath).put("size", target.length()).put("reused", true))
                continue
            }
            val r = runCatching { downloadAndUnpack(asset.url, target, ABI_INFO.getValue(abi).second) }
            r.fold(
                onSuccess = { size -> done.put(JSONObject().put("abi", abi).put("path", target.absolutePath).put("size", size)) },
                onFailure = { e -> failed.put(JSONObject().put("abi", abi).put("reason", e.message ?: e.javaClass.simpleName)) },
            )
        }
        val result = JSONObject()
            .put("action", "download")
            .put("version", version)
            .put("prerelease", rel.isPre)
            .put("ok", done)
            .put("failed", failed)
            .put("next", "place version=$version abi=<abi> dir=<decode 目录>")
        if (done.length() == 0) return err("DOWNLOAD_FAILED", "全部 ABI 都失败了（网络或解压异常）", "failed", failed.toString())
        return ok(result)
    }

    private fun resolveReleases(root: File): List<ReleaseInfo> {
        val json = doVersions(root, 50, false)
        val arr = json.optJSONArray("versions") ?: return emptyList()
        val out = ArrayList<ReleaseInfo>()
        for (i in 0 until arr.length()) {
            val v = arr.getJSONObject(i)
            val abisArr = v.optJSONArray("abis") ?: continue
            val assets = LinkedHashMap<String, AssetInfo>()
            for (j in 0 until abisArr.length()) {
                val abi = abisArr.optString(j)
                if (abi.isBlank()) continue
                assets[abi] = AssetInfo(url = assetUrl(v.optString("version"), abi), size = 0)
            }
            out.add(ReleaseInfo(v.optString("version"), v.optBoolean("prerelease"), assets))
        }
        return out
    }

    private fun assetUrl(version: String, abi: String): String {
        val arch = ABI_INFO.getValue(abi).first
        return "https://github.com/frida/frida/releases/download/$version/frida-gadget-$version-android-$arch.so.xz"
    }

    /**
     * 下载 .xz 并解压成 so。三步校验：解压成功、ELF 魔数、e_machine 与 ABI 一致——
     * 下错架构的 gadget 装进去只会让目标启动即崩，不如在这里就挡住。
     */
    private fun downloadAndUnpack(url: String, target: File, expectMachine: Int): Long {
        target.parentFile?.mkdirs()
        val tmpXz = File(target.parentFile, GADGET_NAME + ".xz.part")
        val tmpSo = File(target.parentFile, GADGET_NAME + ".part")
        try {
            download(url, tmpXz)
            if (tmpXz.length() < 1024) throw IllegalStateException("下载内容过小（${tmpXz.length()} 字节），可能被网关拦截")
            FileOutputStream(tmpSo).use { out ->
                tmpXz.inputStream().use { ins ->
                    XZInputStream(ins).use { xz ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = xz.read(buf)
                            if (n <= 0) break
                            total += n
                            if (total > MAX_DOWNLOAD_BYTES) throw IllegalStateException("解压后超过 ${MAX_DOWNLOAD_BYTES / 1024 / 1024}MB 上限")
                            out.write(buf, 0, n)
                        }
                    }
                }
            }
            val bytes = ByteArray(20)
            tmpSo.inputStream().use { it.read(bytes) }
            if (!(bytes[0].toInt() == 0x7F && bytes[1].toInt() == 0x45 && bytes[2].toInt() == 0x4C && bytes[3].toInt() == 0x46)) {
                throw IllegalStateException("解压后不是 ELF 文件（前 4 字节不符）")
            }
            val machine = (bytes[18].toInt() and 0xFF) or ((bytes[19].toInt() and 0xFF) shl 8)
            if (machine != expectMachine) {
                throw IllegalStateException("架构不符：ELF e_machine=$machine，期望 $expectMachine")
            }
            if (target.exists()) target.delete()
            if (!tmpSo.renameTo(target)) {
                tmpSo.copyTo(target, overwrite = true)
            }
            return target.length()
        } finally {
            runCatching { tmpXz.delete() }
            runCatching { tmpSo.delete() }
        }
    }

    private fun download(url: String, out: File): Unit {
        var current = url
        var redirects = 0
        while (redirects < 6) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 180000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "taffy-nihe")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                        ?: throw IllegalStateException("重定向缺少 Location（HTTP $code）")
                    current = if (loc.startsWith("http")) loc else URL(URL(current), loc).toString()
                    redirects++
                    continue
                }
                if (code != 200) throw IllegalStateException("HTTP $code")
                conn.inputStream.use { ins ->
                    out.outputStream().use { os ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = ins.read(buf)
                            if (n <= 0) break
                            total += n
                            if (total > MAX_DOWNLOAD_BYTES) throw IllegalStateException("下载超过上限")
                            os.write(buf, 0, n)
                        }
                    }
                }
                return
            } finally {
                conn.disconnect()
            }
        }
        throw IllegalStateException("重定向次数过多")
    }

    // ──────────────── import / remove / place ────────────────

    private fun doImport(root: File, args: JSONObject): JSONObject {
        val path = args.str("path")
        if (path.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 path", "path", "")
        val src = File(path)
        if (!src.isFile) return err("FILE_NOT_FOUND", "文件不存在: $path", "path", path)
        var version = args.str("version").ifBlank { "imported" }
        var abi = normalizeAbi(args.str("abi"))

        val bytes = if (src.name.lowercase().endsWith(".xz")) {
            val tmp = File(src.parentFile, src.name + ".unpacked.part")
            try {
                FileOutputStream(tmp).use { out ->
                    src.inputStream().use { ins ->
                        XZInputStream(ins).use { xz ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = xz.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                            }
                        }
                    }
                }
                tmp.readBytes()
            } finally {
                runCatching { tmp.delete() }
            }
        } else {
            if (src.length() > MAX_DOWNLOAD_BYTES) return err("TOO_LARGE", "文件超过上限", "path", path)
            src.readBytes()
        }
        if (bytes.size < 20 || !(bytes[0].toInt() == 0x7F && bytes[1].toInt() == 0x45 && bytes[2].toInt() == 0x4C && bytes[3].toInt() == 0x46)) {
            return err("NOT_ELF", "不是 ELF 文件（既不是 .so 也不是可解压的 .xz）: ${src.name}", "path", path)
        }
        val machine = (bytes[18].toInt() and 0xFF) or ((bytes[19].toInt() and 0xFF) shl 8)
        if (abi.isBlank() || !ABI_INFO.containsKey(abi)) {
            abi = ABI_INFO.entries.firstOrNull { it.value.second == machine }?.key
                ?: return err("UNKNOWN_ARCH", "无法识别 ELF 架构 e_machine=$machine，请显式传 abi", "abi", "")
        }
        val expect = ABI_INFO.getValue(abi).second
        if (expect != machine) return err("ABI_MISMATCH", "ABI=$abi 期望 e_machine=$expect，实际 $machine", "abi", abi)

        val target = File(File(File(root, version), abi), GADGET_NAME)
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { it.write(bytes) }
        return ok(JSONObject()
            .put("action", "import")
            .put("version", version)
            .put("abi", abi)
            .put("path", target.absolutePath)
            .put("size", target.length()))
    }

    private fun doRemove(root: File, args: JSONObject): JSONObject {
        val version = args.str("version")
        if (version.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 version", "version", "")
        val abi = normalizeAbi(args.str("abi"))
        val versionDir = File(root, version)
        if (!versionDir.isDirectory) return err("VERSION_NOT_FOUND", "本地库没有版本 $version", "version", version)
        return if (abi.isNotBlank()) {
            val dir = File(versionDir, abi)
            val removed = runCatching { dir.deleteRecursively() }.getOrDefault(false)
            if (versionDir.listFiles()?.isEmpty() != false) versionDir.deleteRecursively()
            ok(JSONObject().put("action", "remove").put("version", version).put("abi", abi).put("removed", removed))
        } else {
            val removed = runCatching { versionDir.deleteRecursively() }.getOrDefault(false)
            ok(JSONObject().put("action", "remove").put("version", version).put("removed", removed))
        }
    }

    private fun doPlace(root: File, args: JSONObject): JSONObject {
        val dirPath = args.str("dir")
        if (dirPath.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 dir（decode 目录）", "dir", "")
        val decode = File(dirPath)
        if (!decode.isDirectory) return err("FILE_NOT_FOUND", "decode 目录不存在: $dirPath", "dir", dirPath)
        val abi = normalizeAbi(args.str("abi")).ifBlank { "arm64-v8a" }
        if (!ABI_INFO.containsKey(abi)) return err("INVALID_ARGUMENT", "未知 ABI: $abi", "abi", abi)
        var version = args.str("version").ifBlank { "" }
        if (version.isBlank()) {
            version = root.listFiles()?.filter { it.isDirectory }
                ?.filter { File(File(it, abi), GADGET_NAME).isFile }
                ?.maxByOrNull { it.name }?.name ?: ""
        }
        if (version.isBlank()) return err("LIB_NOT_FOUND", "本地库里没有可用的 gadget，先 download 或 import")
        val src = File(File(File(root, version), abi), GADGET_NAME)
        if (!src.isFile) return err("LIB_NOT_FOUND", "本地库没有 $version / $abi 的 gadget", "version", version)
        val libDir = File(decode, "lib/$abi")
        libDir.mkdirs()
        val dst = File(libDir, GADGET_NAME)
        src.copyTo(dst, overwrite = true)
        return ok(JSONObject()
            .put("action", "place")
            .put("version", version)
            .put("abi", abi)
            .put("path", dst.absolutePath)
            .put("size", dst.length())
            .put("next", "taffy_apk_frida_gadget(action=place dir=$dirPath) 配 config 与脚本 → patch_entry → rebuild → sign"))
    }

    // ──────────────── 工具函数 ────────────────

    private fun normalizeAbi(raw: String): String {
        val v = raw.trim()
        if (v.isEmpty()) return ""
        return when (v.lowercase()) {
            "arm64", "arm64-v8a", "aarch64" -> "arm64-v8a"
            "arm", "arm32", "armeabi", "armeabi-v7a" -> "armeabi-v7a"
            "x86", "i386", "i686" -> "x86"
            "x64", "x86_64", "amd64" -> "x86_64"
            else -> v
        }
    }

    private data class AssetInfo(val url: String, val size: Long)
    private data class ReleaseInfo(val version: String, val isPre: Boolean, val assets: Map<String, AssetInfo>)

    val ALL = listOf(gadgetKit)
}
