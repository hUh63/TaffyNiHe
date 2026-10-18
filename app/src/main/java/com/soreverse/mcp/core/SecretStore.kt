package com.soreverse.mcp.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 小体积敏感串（MCP accessToken、AI API Key）的加密落盘。
 *
 * 背景：这些值原本以明文写在 `so_reverse_mcp.xml` 里。App 同时持有 root/Shizuku 通道与
 * MANAGE_EXTERNAL_STORAGE，任何同 uid 运行的代码（例如插件 Python 子进程）或一次取证镜像
 * 都能直接把 shared_prefs 读走，token 一泄漏等于整个 MCP 服务失守。
 *
 * 实现：AndroidKeyStore 内的 AES-256-GCM 密钥加密，密文以 base64(iv||ciphertext) 存独立 prefs。
 * 个别定制 ROM 的 Keystore 会异常，此时降级为明文（`<name>.plain`）并打日志——
 * 宁可保住可用性，也不能因为加固让用户彻底连不上服务。
 */
internal object SecretStore {

    private const val PREFS = "taffy_secrets"
    private const val ALIAS = "taffy_secret_v1"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun put(context: Context, name: String, value: String) {
        val ok = runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val cipherText = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val blob = cipher.iv + cipherText
            prefs(context).edit()
                .putString(name, Base64.encodeToString(blob, Base64.NO_WRAP))
                .remove("$name.plain")
                .apply()
        }.isSuccess
        if (!ok) {
            AppLog.w("SecretStore: AndroidKeyStore unavailable, storing '$name' as plain text")
            // 必须同时删掉旧的密文条目：get() 优先读密文，只写 .plain 会让新值永远读不到
            prefs(context).edit().putString("$name.plain", value).remove(name).apply()
        }
    }

    fun get(context: Context, name: String): String? {
        val raw = prefs(context).getString(name, null)
        if (raw.isNullOrBlank()) return prefs(context).getString("$name.plain", null)
        val plain = runCatching {
            val blob = Base64.decode(raw, Base64.NO_WRAP)
            if (blob.size <= IV_BYTES) return@runCatching null
            val iv = blob.copyOfRange(0, IV_BYTES)
            val cipherText = blob.copyOfRange(IV_BYTES, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        }.getOrNull()
        return plain ?: prefs(context).getString("$name.plain", null)
    }

    fun remove(context: Context, name: String) {
        prefs(context).edit().remove(name).remove("$name.plain").apply()
    }
}
