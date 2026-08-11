package com.soreverse.mcp.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore

/**
 * APK 签名密钥库管理：
 *  - 密钥文件统一存放于 filesDir/keystores/；
 *  - 每个密钥记录（文件名/别名/口令）持久化在 SharedPreferences（mcp_signing_keys JSON 数组）；
 *  - 提供导入（复制外部 keystore 文件）、列表、删除、解析当前密钥。
 *
 * 对应设置页「APK 签名设置 → 其他密钥 → 密钥管理页」以及 MCP 工具 taffy_signing_keys。
 */
object SigningKeyStore {

    private const val PREF_NAME = "mcp_signing_keys"
    private const val KEY_JSON = "keys"

    fun dir(context: Context): File = File(context.applicationContext.filesDir, "keystores").apply { mkdirs() }

    /** 元数据：{name(文件名), alias, storePass, keyPass?} 列表。 */
    private fun rawList(context: Context): JSONArray = runCatching {
        JSONArray(
            context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(KEY_JSON, "[]") ?: "[]",
        )
    }.getOrDefault(JSONArray())

    private fun saveList(context: Context, arr: JSONArray) {
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_JSON, arr.toString()).apply()
    }

    /** 导入 keystore：把外部文件复制到 keystores/，登记元数据。 */
    fun import(
        context: Context,
        sourcePath: String,
        alias: String,
        storePass: String,
        keyPass: String? = null,
    ): JSONObject {
        val src = File(sourcePath)
        if (!src.isFile) {
            return JSONObject().put("ok", false)
                .put("error", JSONObject().put("code", "FILE_NOT_FOUND").put("message", "密钥文件不存在: $sourcePath"))
        }
        val name = sanitizeFileName(src.name)
        if (name.isBlank()) {
            return JSONObject().put("ok", false)
                .put("error", JSONObject().put("code", "BAD_NAME").put("message", "文件名无效"))
        }
        // 复制到 keystores/
        val target = File(dir(context), name)
        if (!target.exists() || target.length() != src.length()) {
            runCatching { src.copyTo(target, overwrite = true) }
                .onFailure { e ->
                    return JSONObject().put("ok", false)
                        .put("error", JSONObject().put("code", "IMPORT_FAILED").put("message", "复制密钥文件失败: ${e.message}"))
                }
        }
        // 验证 keystore 可解析（口令/别名正确）
        val verify = loadKey(context, name, alias, storePass)
        if (verify == null) {
            target.delete()
            return JSONObject().put("ok", false)
                .put("error", JSONObject().put("code", "BAD_KEYSTORE").put("message", "无法用提供的口令/别名打开密钥库，导入失败"))
        }
        // 登记元数据（同名覆盖）
        val arr = rawList(context)
        val cleaned = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o != null && o.optString("name") != name) cleaned.put(o)
        }
        cleaned.put(JSONObject()
            .put("name", name)
            .put("alias", alias)
            .put("storePass", storePass)
            .put("keyPass", keyPass ?: storePass))
        saveList(context, cleaned)
        return JSONObject().put("ok", true)
            .put("name", name)
            .put("alias", alias)
            .put("subject", verify.third)
            .put("hint", "密钥已导入并登记，可在 APK 签名设置中选择使用")
    }

    /** 密钥列表（脱敏：不返回口令）。 */
    fun list(context: Context): JSONArray {
        val arr = rawList(context)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name")
            out.put(JSONObject()
                .put("name", name)
                .put("alias", o.optString("alias"))
                .put("exists", File(dir(context), name).exists())
                .put("subject", runCatching {
                    loadKey(context, name, o.optString("alias"), o.optString("storePass"))?.third ?: ""
                }.getOrDefault("")))
        }
        return out
    }

    /** 删除密钥（文件 + 元数据）。 */
    fun delete(context: Context, name: String): Boolean {
        File(dir(context), name).delete()
        val arr = rawList(context)
        val cleaned = JSONArray()
        var removed = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o != null && o.optString("name") == name) { removed = true; continue }
            if (o != null) cleaned.put(o)
        }
        if (removed) saveList(context, cleaned)
        return removed
    }

    /** 解析 keystore 中的私钥 + 证书。成功返回 (key, cert, subject)，失败返回 null。 */
    fun loadKey(context: Context, name: String, alias: String, storePass: String): Triple<java.security.PrivateKey, java.security.cert.X509Certificate, String>? {
        val file = File(dir(context), name)
        if (!file.isFile || alias.isBlank()) return null
        return runCatching {
            val ks = KeyStore.getInstance("PKCS12")
            file.inputStream().use { ks.load(it, storePass.toCharArray()) }
            val key = ks.getKey(alias, storePass.toCharArray()) as? java.security.PrivateKey ?: return null
            val cert = ks.getCertificate(alias) as? java.security.cert.X509Certificate ?: return null
            Triple(key, cert, cert.subjectDN.toString())
        }.getOrNull()
    }

    /** 解析当前选中的自定义密钥（由设置 apkSignKeySource= custom 驱动）。 */
    fun resolveActive(context: Context): Triple<java.security.PrivateKey, java.security.cert.X509Certificate, String>? {
        val s = SettingsStore(context)
        val name = s.apkSignKeystoreName
        if (name.isBlank()) return null
        // 从元数据取口令/别名
        val arr = rawList(context)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("name") == name) {
                return loadKey(context, name, o.optString("alias"), o.optString("storePass"))
            }
        }
        return null
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(120)
}
