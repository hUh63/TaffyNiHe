package com.soreverse.mcp.mcp

import com.android.apksig.ApkSigner
import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import com.soreverse.mcp.core.intValue
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date

/**
 * 塔菲逆核: APK 回编打包 + 签名(补齐 MT 管理器"改完装回去"的能力)。
 *  - smali_assemble: smali 目录 → dex(smali 库,反 baksmali)
 *  - apk_sign:       给 APK 做 v1/v2/v3 签名(apksig, 自动生成/复用内置签名密钥)
 *
 * 配合已有的 baksmali_decode(dex→smali)/ jadx / apk_decode,形成
 * "反编译 → 改 smali/资源 → 回编 dex → 打包 → 签名" 的完整链路。
 */
object ApkBuildTool {

    private const val KEY_ALIAS = "niehe"
    private const val KEY_PASS = "niehe123"

    /** 内置签名密钥(首次用时生成一个自签名 keystore 存 filesDir,之后复用)。 */
    private fun obtainSigner(dir: File): Pair<PrivateKey, X509Certificate> {
        val ksFile = File(dir, "niehe-sign.jks")
        val ks = KeyStore.getInstance("PKCS12")
        if (ksFile.exists()) {
            ksFile.inputStream().use { ks.load(it, KEY_PASS.toCharArray()) }
            val key = ks.getKey(KEY_ALIAS, KEY_PASS.toCharArray()) as PrivateKey
            val cert = ks.getCertificate(KEY_ALIAS) as X509Certificate
            return key to cert
        }
        // 生成 RSA 2048 + 自签名 X509v3 证书(30 年有效)
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 3600 * 1000)
        val notAfter = Date(now + 30L * 365 * 24 * 3600 * 1000)
        val dn = X500Name("CN=Taffy, O=Taffy, C=CN")
        val builder = JcaX509v3CertificateBuilder(
            dn, BigInteger.valueOf(now), notBefore, notAfter, dn, kp.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        // 存 keystore 复用
        ks.load(null, null)
        ks.setKeyEntry(KEY_ALIAS, kp.private, KEY_PASS.toCharArray(), arrayOf(cert))
        ksFile.outputStream().use { ks.store(it, KEY_PASS.toCharArray()) }
        return kp.private to cert
    }

    /** smali → dex。 */
    val smaliAssemble: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "smali_assemble",
            "【smali→dex 回编】把 smali 目录汇编成 dex 文件(baksmali 的逆操作)。改完 smali 后用它生成新 dex。",
            "Assemble a smali directory back into a dex file (reverse of baksmali).",
            "build", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "smaliDir" str "smali 源码目录(绝对路径)"
                "outDex" str "输出 dex 文件路径(默认 smaliDir 同级 out.dex)"
                "apiLevel" int "dex api level(默认 34)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val smaliDir = args.str("smaliDir")
            if (smaliDir.isBlank()) return err("INVALID_ARGUMENT", "缺少 smaliDir", "smaliDir", "")
            val dir = File(smaliDir)
            if (!dir.isDirectory) return err("DIR_NOT_FOUND", "smali 目录不存在: $smaliDir", "smaliDir", smaliDir)
            return runCatching {
                val out = args.str("outDex").ifBlank { File(dir.parentFile, "out.dex").absolutePath }
                val opts = SmaliOptions().apply {
                    outputDexFile = out
                    apiLevel = args.intValue("apiLevel", 34)
                    jobs = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
                }
                val success = Smali.assemble(opts, listOf(smaliDir))
                if (success) ok(JSONObject().put("tool", "smali_assemble").put("success", true).put("outDex", out))
                else err("SMALI_ASSEMBLE_FAILED", "smali 汇编失败(检查 smali 语法)", "smaliDir", smaliDir)
            }.getOrElse { e -> err("SMALI_ASSEMBLE_FAILED", "smali 汇编异常: ${e.message ?: e.javaClass.simpleName}", "smaliDir", smaliDir) }
        }
    }

    /** APK 签名。 */
    val apkSign: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "apk_sign",
            "【APK 签名】用内置密钥给 APK 做 v1/v2/v3 签名,签完即可安装。改完/回编后的 APK 用它签名。首次自动生成签名密钥(存本地复用)。",
            "Sign an APK with v1/v2/v3 schemes using a built-in auto-generated key, so it can be installed.",
            "build", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "inputApk" str "待签名 APK 路径(绝对路径)"
                "outputApk" str "签名后输出路径(默认 输入名-signed.apk)"
                "minSdk" int "最低 SDK(默认 26)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val inputPath = args.str("inputApk")
            if (inputPath.isBlank()) return err("INVALID_ARGUMENT", "缺少 inputApk", "inputApk", "")
            val input = File(inputPath)
            if (!input.isFile) return err("FILE_NOT_FOUND", "APK 不存在: $inputPath", "inputApk", inputPath)
            return runCatching {
                val output = args.str("outputApk").ifBlank {
                    File(input.parentFile, "${input.nameWithoutExtension}-signed.apk").absolutePath
                }
                val (key, cert) = obtainSigner(ctx.context.filesDir)
                val signerConfig = ApkSigner.SignerConfig.Builder("NIEHE", key, listOf(cert)).build()
                ApkSigner.Builder(listOf(signerConfig))
                    .setInputApk(input)
                    .setOutputApk(File(output))
                    .setMinSdkVersion(args.intValue("minSdk", 26))
                    .setV1SigningEnabled(true)
                    .setV2SigningEnabled(true)
                    .setV3SigningEnabled(true)
                    .build()
                    .sign()
                ok(JSONObject()
                    .put("tool", "apk_sign")
                    .put("success", true)
                    .put("outputApk", output)
                    .put("signer", "CN=Taffy (内置自签名密钥)")
                    .put("hint", "已签名,可直接安装。用内置密钥,与官方签名不同,覆盖安装原 App 需先卸载。"))
            }.getOrElse { e -> err("APK_SIGN_FAILED", "APK 签名失败: ${e.message ?: e.javaClass.simpleName}", "inputApk", inputPath) }
        }
    }
}
