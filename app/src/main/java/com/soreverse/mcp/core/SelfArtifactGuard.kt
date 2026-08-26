package com.soreverse.mcp.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 自身 Artifact 守卫（借鉴上游 SOMCP 1.0.20 SelfArtifactGuard）。
 *
 * 塔菲逆核绝不能打开、查看或修改自身的 APK 及其内置 SO——否则 MCP 客户端
 * （或桥接合并进 tools/list 的外部 APK MCP，如 MT 管理器）可被指向塔菲自身
 * 的 artifact，读取/篡改运行中应用的文件，甚至用签名工具破坏自身完整性校验。
 *
 * 三层低误报识别：
 *  1. 运行中 APK 路径（packageCodePath / sourceDir canonical）——无论装在哪；
 *  2. nativeLibraryDir——安装目录里塔菲自带的 SO；
 *  3. 签名层（懒检查 + 缓存）：任意位置的 APK 副本，若签名者摘要与
 *     [com.soreverse.mcp.nativecore.SignatureVerifier] 内嵌 pin 一致，即为自身副本
 *     （位置无关，防改名字/挪目录绕过）。
 *  另含 `lib/<abi>/<name>.so` APK 条目引用判定：条目名命中自身库名即拦截。
 */
object SelfArtifactGuard {

    private val digestCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    // ------------------------------------------------------------- 自身标识

    /** 运行中塔菲 APK 的 canonical 路径。 */
    fun runningApkPaths(context: Context): List<String> {
        val paths = LinkedHashSet<String>()
        runCatching { context.packageCodePath }.getOrNull()?.takeIf { it.isNotBlank() }?.let(paths::add)
        runCatching { context.applicationInfo?.sourceDir }.getOrNull()?.takeIf { it.isNotBlank() }?.let(paths::add)
        return paths.map(::canonical)
    }

    /** 塔菲自带 native 库所在目录（canonical）。 */
    fun nativeLibraryDir(context: Context): String? =
        runCatching { context.applicationInfo?.nativeLibraryDir }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let(::canonical)

    /** 塔菲实际打包的 .so 文件名集合（来自安装目录）。 */
    fun ownLibraryNames(context: Context): Set<String> {
        val root = nativeLibraryDir(context) ?: return emptySet()
        return runCatching {
            File(root).listFiles { f -> f.isFile && f.name.endsWith(".so", ignoreCase = true) }
                ?.map { it.name }
                ?.toSet().orEmpty()
        }.getOrDefault(emptySet())
    }

    // ------------------------------------------------------------- 判定

    /** [path] 是否指向运行中的塔菲 APK 本体。 */
    fun isSelfApkPath(context: Context, path: String): Boolean =
        runningApkPaths(context).any { sameFile(it, canonical(path)) }

    /** [path] 是否是安装目录里的塔菲自带 SO。 */
    fun isSelfBundledSo(context: Context, path: String): Boolean {
        val dir = nativeLibraryDir(context) ?: return false
        val c = canonical(path)
        return c == dir || c.startsWith("$dir/")
    }

    /** [value] 是否是 `lib/<abi>/<name>.so` 形式且 name 是自身库名。 */
    fun isOwnLibEntryReference(context: Context, value: String): Boolean {
        val names = ownLibraryNames(context)
        if (names.isEmpty()) return false
        val entry = value.substringBefore('!').substringAfterLast('/')
        return entry.endsWith(".so", true) && names.any { it.equals(entry, ignoreCase = true) } &&
            (value.startsWith("lib/") || value.contains("/lib/") || value.contains('!'))
    }

    /**
     * [value]（任意参数值）是否引用塔菲自身 artifact。
     * 签名层仅对 .apk 路径懒检查并缓存结果。
     */
    fun isSelfArtifact(context: Context, value: String): Boolean {
        if (value.isBlank()) return false
        if (isSelfApkPath(context, value)) return true
        if (isSelfBundledSo(context, value)) return true
        if (isOwnLibEntryReference(context, value)) return true
        // 签名层: 任意位置的 APK 副本
        val clean = value.substringBefore('!')
        if (clean.endsWith(".apk", true) && clean.startsWith("/")) {
            return digestCache.computeIfAbsent(canonical(clean)) { p ->
                runCatching {
                    com.soreverse.mcp.nativecore.SignatureVerifier.isSelfSignedApk(p)
                }.getOrDefault(false)
            }
        }
        return false
    }

    // ------------------------------------------------------------- 参数扫描

    /** 值看起来像文件/路径引用才做完整判定（快速排除普通字符串）。 */
    private fun looksLikePath(value: String): Boolean =
        value.startsWith("/") || value.endsWith(".apk", true) ||
            value.endsWith(".so", true) || value.contains("lib/") || value.startsWith("content://")

    /**
     * 递归扫描工具参数（JSONObject/JSONArray/字符串），返回第一个引用自身
     * artifact 的值；无则 null。仅扫描路径样字符串，避免普通文本误伤。
     */
    fun findSelfArg(context: Context, args: JSONObject): String? {
        scanJson(context, args, 0)?.let { return it }
        return null
    }

    private fun scanJson(context: Context, node: Any?, depth: Int): String? {
        if (depth > 6) return null
        when (node) {
            is JSONObject -> {
                for (key in node.keys()) {
                    scanJson(context, node.opt(key), depth + 1)?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    scanJson(context, node.opt(i), depth + 1)?.let { return it }
                }
            }
            is String -> if (looksLikePath(node) && isSelfArtifact(context, node)) return node
        }
        return null
    }

    /** 统一的拒绝响应（MCP error payload）。 */
    fun forbidden(arg: String, tool: String): JSONObject =
        err(
            "SELF_ANALYSIS_FORBIDDEN",
            "塔菲逆核拒绝 $tool 对自身 artifact 的操作（$arg）：不能打开/查看/修改自身 APK 或内置 SO。",
            "path", arg,
        ).apply {
            put("hint", "选择其他目标文件；塔菲自身的 APK/SO 不属于可分析对象。")
        }

    // ------------------------------------------------------------- 工具

    private fun canonical(path: String): String = runCatching {
        File(path).canonicalPath
    }.getOrDefault(path)

    private fun sameFile(a: String, b: String): Boolean =
        a == b || runCatching { File(a).canonicalFile == File(b).canonicalFile }.getOrDefault(a == b)
}
