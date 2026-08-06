package com.soreverse.mcp.mcp

import com.android.apksig.ApkVerifier
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.cert.X509Certificate

/**
 * 塔菲逆核: APK 签名证书查看(用已有 apksig, 纯 Java)。
 *
 * 读 APK 的 v1/v2/v3/v4 签名方案、签名者证书(主体/颁发者/序列号/有效期)、
 * 证书指纹(MD5/SHA1/SHA256)。用途: 校验签名真伪、拿原包签名指纹(比对是否被二次打包)、
 * 判断用了哪些签名方案。
 */
object ApkSignInfoTool {

    private fun fingerprint(cert: X509Certificate, algo: String): String =
        MessageDigest.getInstance(algo).digest(cert.encoded)
            .joinToString(":") { "%02X".format(it) }

    val info: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "apk_sign_info",
            "【APK 签名证书查看】读 APK 的签名方案(v1/v2/v3/v4)、签名者证书(主体 CN/O、颁发者、序列号、有效期)、证书指纹(MD5/SHA1/SHA256)。用于校验签名真伪、拿原包指纹比对是否被二次打包、看用了哪些签名方案。",
            "Inspect APK signature: schemes (v1/v2/v3/v4), signer certificate (subject/issuer/serial/validity), and fingerprints (MD5/SHA1/SHA256). For verifying signatures, comparing original fingerprints against repackaged APKs, and checking which schemes are used.",
            "build", ToolClass.EXTRA, heavy = false,
        ) {
            objectSchema(props {
                "path" str "APK 文件绝对路径"
                "filePath" str "path 的别名"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val inputPath = args.str("path").ifBlank { args.str("filePath") }
            if (inputPath.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 path(APK 路径)", "path", "")
            val input = File(inputPath)
            if (!input.isFile) return err("FILE_NOT_FOUND", "文件不存在: $inputPath", "path", inputPath)

            return runCatching {
                val result = ApkVerifier.Builder(input).build().verify()
                val certs = JSONArray()
                result.signerCertificates.forEach { cert ->
                    certs.put(JSONObject()
                        .put("subject", cert.subjectX500Principal.name)
                        .put("issuer", cert.issuerX500Principal.name)
                        .put("serialNumber", cert.serialNumber.toString(16))
                        .put("notBefore", cert.notBefore.toString())
                        .put("notAfter", cert.notAfter.toString())
                        .put("sigAlgorithm", cert.sigAlgName)
                        .put("md5", fingerprint(cert, "MD5"))
                        .put("sha1", fingerprint(cert, "SHA-1"))
                        .put("sha256", fingerprint(cert, "SHA-256")))
                }
                ok(JSONObject()
                    .put("tool", "apk_sign_info")
                    .put("path", inputPath)
                    .put("verified", result.isVerified)
                    .put("schemes", JSONObject()
                        .put("v1", result.isVerifiedUsingV1Scheme)
                        .put("v2", result.isVerifiedUsingV2Scheme)
                        .put("v3", result.isVerifiedUsingV3Scheme)
                        .put("v4", result.isVerifiedUsingV4Scheme))
                    .put("signerCount", certs.length())
                    .put("signers", certs)
                    .put("hint", "sha256 指纹可比对原包与二次打包包是否同签名; 校验/绕过签名检测参考"))
            }.getOrElse { e ->
                err("SIGN_INFO_FAILED", "读取签名失败: ${e.message ?: e.javaClass.simpleName}", "path", inputPath)
            }
        }
    }
}
