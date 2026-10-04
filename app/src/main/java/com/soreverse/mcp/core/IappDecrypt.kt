package com.soreverse.mcp.core

import java.util.ArrayDeque
import java.util.Locale

/**
 * iApp v3 加密包解密引擎。移植自 aiysss/iapp-decrypt（MIT）。
 *
 * lib.so 是两层结构：外层 AES-CBC 容器 + 内层按「成员标记」分隔的成员源码文件；
 * 支持三套算法族 current（现代）/ legacy4（远古 md5 链）/ transitional（过渡期 mete）。
 */
object IappDecrypt {

    // ── 内置密钥常量 ──────────────────────────────────────────────
    val MAGIC_STRING = byteArrayOf(
        0xE2.toByte(), 0x5F, 0x48, 0x73, 0x25, 0xC6.toByte(), 0xE7.toByte(), 0x11, 0x80.toByte(), 0x7C,
        0x46, 0xC3.toByte(), 0xE3.toByte(), 0x1D, 0x3C, 0x97.toByte(), 0x3C, 0x8C.toByte(), 0x1E, 0x01,
    )
    val MAGIC_BYTES = byteArrayOf(
        0xE2.toByte(), 0x5F, 0x48, 0x73, 0x25, 0xC6.toByte(), 0xE7.toByte(), 0x11, 0x80.toByte(), 0x7C,
        0x46, 0xC3.toByte(), 0xE3.toByte(), 0x1D, 0x3C, 0x97.toByte(), 0x3C, 0x77, 0x1E, 0x01,
    )
    val BURDEN_XOR_KEY = byteArrayOf(
        0xE2.toByte(), 0x48, 0x25, 0xE7.toByte(), 0x80.toByte(), 0x46, 0xE3.toByte(), 0x3C, 0x3C, 0x1E,
        0x25, 0x1D, 0x4E, 0x05, 0x55, 0xE1.toByte(), 0x69, 0xA8.toByte(), 0x18, 0xCA.toByte(),
    )

    private val REF_SUFFIXES = setOf("iyu", "myu", "ilua", "mlua", "ijava", "mjava", "ijs", "mjs", "java", "yul", "html")
    private val REF_RE = Regex("(?i)([A-Za-z0-9_./\\-\\u4e00-\\u9fa5]+\\.(?:iyu|myu|ilua|mlua|ijava|mjava|ijs|mjs|java|yul|html))")
    private val FN_RE = Regex("(?i)fn\\s+([A-Za-z0-9_/\\-\\u4e00-\\u9fa5]+)\\.[A-Za-z0-9_/\\-\\u4e00-\\u9fa5]+")
    private val CALL_RE = Regex("(?i)call\\s*\\([^,]+,\\s*[\"']([^\"']+)[\"']\\s*,\\s*[\"']([A-Za-z0-9_/\\-\\u4e00-\\u9fa5]+)\\.[^\"']+[\"']")

    private const val LEGACY_LOCAL_MARKER = "=/eyVRDWJWJF4zIwoyZyZPfQ=="
    private val LEGACY_OLD_SO_MARKER = byteArrayOf(0x00, 0x61, 0x61, 0x00, 0x6C, 0x69, 0x62, 0x2E, 0x73, 0x6F)
    private val LEGACY_REF_RE = Regex(
        "([A-Za-z0-9_\\-\\u4e00-\\u9fa5./]+\\.(?:iyu|ijava|ilua|ijs|myu|mjava|mlua|mjs))" +
            "|(fn [A-Za-z0-9_\\-\\u4e00-\\u9fa5]+)" +
            "|(call\\(.*\\))",
        RegexOption.IGNORE_CASE,
    )
    private val LEGACY_CALL_MODULE_RE = Regex("([A-Za-z0-9_\\-\\u4e00-\\u9fa5]+\\.)")

    // ── 模型 ─────────────────────────────────────────────────────
    data class IappConfig(
        val packageName: String,
        val versionName: String,
        val versionCode: String,
        val appName: String,
        val sok: String = "",
        val dek: String = "",
        val entryFile: String = "mian.iyu",
        val signKey: String = "",
        val signB64: String = "",
        val pwdKey: String = "",
    )

    class KeySet(val name: String, val postKey: ByteArray, val xorKey: ByteArray)

    class Result(
        val container: ByteArray,
        val files: LinkedHashMap<String, ByteArray>,
        val failed: List<String>,
        val trace: LinkedHashMap<String, String>,
    )

    private class Candidate(val name: String, val confidence: Int, val derive: (Map<String, ByteArray>, KeySet) -> ByteArray)
    private class MemberStrategy(val markerPostKey: ByteArray?, val memberPostKey: ByteArray?, val keyMode: String, val label: String)
    private class OuterHit(val outerKey: ByteArray, val container: ByteArray, val strategy: MemberStrategy, val entryPlain: ByteArray)

    // ── 公共入口 ─────────────────────────────────────────────────
    fun normalizeMemberName(name: String): String =
        name.trim().trim('"', '\'').replace('\\', '/').lowercase(Locale.ROOT)

    fun generateKeySets(nativeSo: ByteArray? = null, manualPost: ByteArray? = null, manualXor: ByteArray? = null): List<KeySet> {
        // 从原生库启发式提取密钥表候选（提高非默认密钥包的命中率）；默认密钥优先试。
        val soCands = if (nativeSo != null) IappElfKeys.extractCandidates(nativeSo) else emptyList()
        val posts = ArrayList<ByteArray>()
        if (manualPost != null) posts.add(manualPost)
        posts.add(MAGIC_BYTES)
        posts.addAll(soCands)
        val xors = ArrayList<ByteArray>()
        if (manualXor != null) xors.add(manualXor)
        xors.add(BURDEN_XOR_KEY)
        xors.addAll(soCands)
        val postsU = uniqBytes(posts).take(5)
        val xorsU = uniqBytes(xors).take(5)
        val out = ArrayList<KeySet>()
        val seen = HashSet<String>()
        for (pi in postsU.indices) for (xi in xorsU.indices) {
            val p = postsU[pi]; val x = xorsU[xi]
            if (!seen.add(p.toHex() + ":" + x.toHex())) continue
            val name = if (p.contentEquals(MAGIC_BYTES) && x.contentEquals(BURDEN_XOR_KEY)) "generic" else "post[$pi]/xor[$xi]"
            out.add(KeySet(name, p, x))
            if (out.size >= 12) return out
        }
        return out
    }

    private fun uniqBytes(list: List<ByteArray>): List<ByteArray> {
        val seen = HashSet<String>(); val out = ArrayList<ByteArray>()
        for (b in list) if (seen.add(b.toHex())) out.add(b)
        return out
    }

    fun resolveConfig(config: IappConfig): IappConfig {
        val sok = config.sok.trim()
        val resolved = IappConfig(
            packageName = config.packageName.trim(),
            versionName = config.versionName.trim(),
            versionCode = config.versionCode.trim(),
            appName = config.appName.trim(),
            sok = sok,
            dek = config.dek.trim(),
            entryFile = normalizeMemberName(config.entryFile.ifBlank { "mian.iyu" }),
            signKey = config.signKey.trim(),
            signB64 = config.signB64.trim(),
            pwdKey = config.pwdKey.trim(),
        )
        val missing = ArrayList<String>()
        if (resolved.packageName.isEmpty()) missing.add("package_name")
        if (resolved.versionName.isEmpty()) missing.add("version_name")
        if (resolved.versionCode.isEmpty()) missing.add("version_code")
        if (resolved.appName.isEmpty()) missing.add("app_name")
        if (resolved.sok.isEmpty()) missing.add("sok")
        if (resolved.dek.isEmpty()) missing.add("dek")
        if (missing.isNotEmpty()) throw IllegalArgumentException("missing decrypt fields: " + missing.joinToString(","))
        return resolved
    }

    fun decrypt(libSo: ByteArray, config: IappConfig, mode: String = "auto", nativeSo: ByteArray? = null): Result {
        val resolved = resolveConfig(config)
        return when (mode.trim().lowercase(Locale.ROOT)) {
            "legacy4" -> decryptLegacy4(libSo, resolved)
            "transitional" -> decryptTransitional(libSo, resolved)
            "current", "legacy", "auto" -> if (mode.trim().lowercase(Locale.ROOT) == "auto" && resolved.sok.length <= 4) {
                runCatching { decryptLegacy4(libSo, resolved) }.getOrElse { decryptCurrent(libSo, resolved, nativeSo) }
            } else {
                decryptCurrent(libSo, resolved, nativeSo)
            }
            else -> throw IllegalArgumentException("mode must be auto, current, legacy, legacy4 or transitional")
        }
    }

    /** 从原生 so 中提取 sok（10 个 Q 特征串前 10 位 + QQQQQQQQQQ + 后 12 位），失败回退 4 字符远古标记。 */
    fun extractSokFromSo(soData: ByteArray): String? {
        val pattern = "QQQQQQQQQQ".toByteArray(Charsets.US_ASCII)
        val pos = soData.indexOfPattern(pattern)
        if (pos < 0) {
            val legacyPos = soData.indexOfPattern(LEGACY_OLD_SO_MARKER)
            if (legacyPos >= 4) {
                val cand = soData.copyOfRange(legacyPos - 4, legacyPos)
                val text = String(cand, Charsets.US_ASCII)
                if (text.length == 4 && text.all { it.code in 32..126 }) return text
            }
            return null
        }
        if (pos < 10 || pos + 10 + 12 > soData.size) return null
        val result = soData.copyOfRange(pos - 10, pos + 10 + 12)
        val text = String(result, Charsets.US_ASCII)
        return if (text.all { it.code in 32..126 }) text else null
    }

    // ── current 族 ───────────────────────────────────────────────
    private fun decryptCurrent(libSo: ByteArray, config: IappConfig, nativeSo: ByteArray? = null): Result {
        val hasSig = config.signB64.isNotBlank()
        val candidates = generateOuterCandidates(hasSig)
        val keySets = generateKeySets(nativeSo)
        var tried = 0
        for (ks in keySets) for (c in candidates) {
            tried++
            val hit = verifyOuterCandidate(libSo, config, ks, c) ?: continue
            val (extracted, failed) = crawlMembers(hit.container, config, ks, hit.strategy)
            extracted.putIfAbsent(normalizeMemberName(config.entryFile), hit.entryPlain)
            val trace = LinkedHashMap<String, String>()
            trace["algorithm_family"] = "current"
            trace["candidate"] = c.name
            trace["key_set"] = ks.name
            trace["member_strategy"] = hit.strategy.label
            trace["outer_key"] = hit.outerKey.toHex()
            trace["resolved_sok"] = config.sok
            trace["resolved_dek"] = config.dek
            trace["candidate_count"] = candidates.size.toString()
            trace["key_set_count"] = keySets.size.toString()
            trace["tried_count"] = tried.toString()
            trace["post_key"] = ks.postKey.toHex()
            trace["xor_key"] = ks.xorKey.toHex()
            return Result(hit.container, extracted, failed, trace)
        }
        throw RuntimeException("decrypt failed after trying $tried candidate combinations")
    }

    private fun verifyOuterCandidate(libSo: ByteArray, config: IappConfig, ks: KeySet, c: Candidate): OuterHit? {
        val env = buildOuterEnv(config)
        val outerKey = try { c.derive(env, ks) } catch (e: Exception) { return null }
        val container = try { IappCrypto.aesCbcThenXorDecrypt(libSo, outerKey) } catch (e: Exception) { return null }
        val entryName = normalizeMemberName(config.entryFile)
        for (st in memberStrategies(ks, null)) {
            val plain = tryDecryptMember(container, entryName, config, ks.xorKey, st) ?: continue
            if (!looksLikeIappPlain(plain)) continue
            return OuterHit(outerKey, container, st, plain)
        }
        return null
    }

    private fun buildOuterEnv(config: IappConfig): Map<String, ByteArray> {
        val env = HashMap<String, ByteArray>()
        val pkg = config.packageName.toByteArray(Charsets.UTF_8)
        val ver = config.versionName.toByteArray(Charsets.UTF_8)
        val code = config.versionCode.toByteArray(Charsets.UTF_8)
        val app = config.appName.toByteArray(Charsets.UTF_8)
        val sok = config.sok.toByteArray(Charsets.UTF_8)
        val dek = config.dek.toByteArray(Charsets.UTF_8)
        env["package_name"] = pkg
        env["version_name"] = ver
        env["version_code"] = code
        env["app_name"] = app
        env["sok"] = sok
        env["dek"] = dek
        env["empty"] = ByteArray(0)
        val signB64 = config.signB64.replace(Regex("\\s+"), "").toByteArray(Charsets.UTF_8)
        env["sign_b64"] = signB64
        env["signature"] = try {
            if (signB64.isNotEmpty()) java.util.Base64.getDecoder().decode(String(signB64, Charsets.US_ASCII)) else ByteArray(0)
        } catch (e: Exception) { ByteArray(0) }
        env["entry_file"] = normalizeMemberName(config.entryFile).toByteArray(Charsets.UTF_8)
        env["base"] = sok + ver + pkg + app + code + dek
        env["base_tail"] = ver + pkg + app + code + dek
        env["sok_dek"] = sok + dek
        env["dek_sok"] = dek + sok
        return env
    }

    private fun envOf(env: Map<String, ByteArray>, key: String): ByteArray = env[key] ?: ByteArray(0)

    private fun resolvePostKeyMode(mode: String, ks: KeySet): ByteArray? = when (mode) {
        "magic_string" -> MAGIC_STRING
        "magic_bytes" -> MAGIC_BYTES
        "keyset" -> ks.postKey
        "nopost" -> null
        else -> throw IllegalArgumentException("unknown post key mode: $mode")
    }

    private fun idbfjSeed(env: Map<String, ByteArray>, passwordName: String?, mode: String, postKey: ByteArray?): ByteArray {
        val password = if (passwordName != null && passwordName != "none") envOf(env, passwordName) else ByteArray(0)
        val deriv = envOf(env, "base") + password
        val caseIdx = when (mode) {
            "unsigned" -> {
                var s = deriv.size
                for (b in deriv) s += b.toInt() and 0xFF
                ((s + (deriv[0].toInt() and 0xFF) * (deriv[deriv.size - 1].toInt() and 0xFF)) % 6 + 6) % 6
            }
            "signed" -> {
                var ss = deriv.size
                for (b in deriv) ss += IappCrypto.toSigned(b.toInt() and 0xFF)
                IappCrypto.signedMod(ss + IappCrypto.toSigned(deriv[0].toInt() and 0xFF) * IappCrypto.toSigned(deriv[deriv.size - 1].toInt() and 0xFF), 6)
            }
            else -> throw IllegalArgumentException("unknown idbfj mode: $mode")
        }
        val seconds = listOf("version_name", "package_name", "app_name", "version_code", "sok", "dek")
        val second = envOf(env, seconds[caseIdx])
        return IappCrypto.slky(deriv, second, postKey)
    }

    private fun idbfjPick6(data: ByteArray): Int {
        if (data.isEmpty()) return 0
        var signedSum = data.size
        for (b in data) signedSum += IappCrypto.toSigned(b.toInt() and 0xFF)
        val value = signedSum + IappCrypto.toSigned(data[0].toInt() and 0xFF) * IappCrypto.toSigned(data[data.size - 1].toInt() and 0xFF)
        return IappCrypto.signedMod(value, 6)
    }

    private fun idbfjPick6Unsigned(data: ByteArray): Int {
        if (data.isEmpty()) return 0
        var s = data.size
        for (b in data) s += b.toInt() and 0xFF
        return (s + (data[0].toInt() and 0xFF) * (data[data.size - 1].toInt() and 0xFF)) % 6
    }

    private fun legacyOuterCandidate(name: String, idbfjMode: String, passwordName: String?, p19Post: String, p24Post: String, transform: String, confidence: Int) =
        Candidate(name, confidence) { env, ks ->
            val p19Key = resolvePostKeyMode(p19Post, ks)
            val p24Key = resolvePostKeyMode(p24Post, ks)
            val p19 = idbfjSeed(env, passwordName, idbfjMode, p19Key)
            val p24 = IappCrypto.slky(p19, p19, p24Key)
            val p25 = IappCrypto.slky(p19, p24, p24Key)
            when (transform) {
                "xor" -> IappCrypto.cyclicXor(p25, ks.xorKey).copyOf(16)
                "direct" -> p25.copyOf(16)
                else -> throw IllegalArgumentException("unknown transform: $transform")
            }
        }

    private fun envP25Candidate(firstName: String, secondName: String, postMode: String, transform: String, confidence: Int) =
        Candidate("env:$firstName:$secondName:$postMode:$transform", confidence) { env, ks ->
            val first = envOf(env, firstName)
            val second = envOf(env, secondName)
            val post = resolvePostKeyMode(postMode, ks)
            val p19 = IappCrypto.slky(first, second, post)
            val p24 = IappCrypto.slky(p19, p19, post)
            val p25 = IappCrypto.slky(p19, p24, post)
            when (transform) {
                "xor" -> IappCrypto.cyclicXor(p25, ks.xorKey).copyOf(16)
                "direct" -> p25.copyOf(16)
                else -> throw IllegalArgumentException("unknown transform: $transform")
            }
        }

    private fun nativeX86IdbfjP25Candidate(inputName: String, confidence: Int) =
        Candidate("native:x86_idbfj_$inputName:p19_p24_p25:xor", confidence) { env, ks ->
            val inp = envOf(env, inputName)
            val hidden = envOf(env, "sok")
            val base = envOf(env, "base")
            val first = if (inp.contentEquals(hidden)) base else base + inp
            val seconds = listOf("version_name", "package_name", "app_name", "version_code", "sok", "dek").map { envOf(env, it) }
            val pick = idbfjPick6(first)
            if (pick < 0 || pick >= seconds.size) throw IllegalArgumentException("idbfj pick out of range: $pick")
            val p19 = IappCrypto.slky(first, seconds[pick], ks.postKey)
            val p24 = IappCrypto.slky(p19, p19, ks.postKey)
            val p25 = IappCrypto.slky(p19, p24, ks.postKey)
            IappCrypto.cyclicXor(p25, ks.xorKey).copyOf(16)
        }

    private fun nativeX86IdbfjP25SignatureCandidate(inputName: String, confidence: Int) =
        Candidate("native:x86_idbfj_$inputName:p19_sig_p25:xor", confidence) { env, ks ->
            val signature = envOf(env, "signature")
            if (signature.isEmpty()) throw IllegalArgumentException("signature required")
            val inp = envOf(env, inputName)
            val hidden = envOf(env, "sok")
            val base = envOf(env, "base")
            val first = if (inp.contentEquals(hidden)) base else base + inp
            val seconds = listOf("version_name", "package_name", "app_name", "version_code", "sok", "dek").map { envOf(env, it) }
            val pick = idbfjPick6(first)
            if (pick < 0 || pick >= seconds.size) throw IllegalArgumentException("idbfj pick out of range: $pick")
            val p19 = IappCrypto.slky(first, seconds[pick], ks.postKey)
            val p24 = IappCrypto.slky(signature, p19, ks.postKey)
            val p25 = IappCrypto.slky(p19, p24, ks.postKey)
            IappCrypto.cyclicXor(p25, ks.xorKey).copyOf(16)
        }

    private fun transitionalCandidate() =
        Candidate("transitional:unsigned:none:p19_sig_p25:direct", 98) { env, _ ->
            val signature = envOf(env, "signature")
            val first = envOf(env, "base")
            val seconds = listOf("version_name", "package_name", "app_name", "version_code", "sok", "dek").map { envOf(env, it) }
            val pick = idbfjPick6Unsigned(first)
            val p19 = IappCrypto.slky(first, seconds[pick], null)
            val p24 = if (signature.isNotEmpty()) IappCrypto.slky(signature, p19, null) else IappCrypto.slky(p19, p19, null)
            val p25 = IappCrypto.slky(p19, p24, null)
            p25.copyOf(16)
        }

    private fun generateOuterCandidates(hasSignature: Boolean): List<Candidate> {
        val out = ArrayList<Candidate>()
        out.add(legacyOuterCandidate("legacy:unsigned:none:magic_string:keyset:xor", "unsigned", null, "magic_string", "keyset", "xor", 100))
        out.add(nativeX86IdbfjP25Candidate("sok", 99))
        out.add(transitionalCandidate())
        if (hasSignature) out.add(nativeX86IdbfjP25SignatureCandidate("sok", 98))
        out.add(legacyOuterCandidate("legacy:unsigned:none:magic_string:magic_bytes:xor", "unsigned", null, "magic_string", "magic_bytes", "xor", 96))
        out.add(legacyOuterCandidate("legacy:unsigned:none:keyset:keyset:xor", "unsigned", null, "keyset", "keyset", "xor", 94))
        out.add(legacyOuterCandidate("legacy:signed:none:magic_string:keyset:xor", "signed", null, "magic_string", "keyset", "xor", 92))
        out.add(legacyOuterCandidate("legacy:unsigned:none:nopost:nopost:direct", "unsigned", null, "nopost", "nopost", "direct", 84))
        out.add(legacyOuterCandidate("legacy:signed:none:nopost:nopost:direct", "signed", null, "nopost", "nopost", "direct", 80))
        for ((password, conf) in listOf("sok" to 90, "entry_file" to 82, "empty" to 78, "app_name" to 74, "package_name" to 72)) {
            out.add(legacyOuterCandidate("legacy:unsigned:$password:magic_string:keyset:xor", "unsigned", password, "magic_string", "keyset", "xor", conf))
            out.add(legacyOuterCandidate("legacy:signed:$password:magic_string:keyset:xor", "signed", password, "magic_string", "keyset", "xor", maxOf(40, conf - 12)))
        }
        val firstNames = listOf("base", "sok", "base_tail", "entry_file", "package_name", "app_name")
        val secondNames = listOf("sok", "dek", "version_name", "package_name", "app_name", "version_code")
        for (f in firstNames) for (s in secondNames) {
            if (f == s) continue
            out.add(envP25Candidate(f, s, "keyset", "xor", 62))
            out.add(envP25Candidate(f, s, "magic_string", "xor", 58))
            out.add(envP25Candidate(f, s, "magic_bytes", "xor", 56))
            out.add(envP25Candidate(f, s, "nopost", "direct", 52))
        }
        val sorted = out.sortedWith(compareBy({ -it.confidence }, { it.name }))
        val seen = HashSet<String>()
        val deduped = ArrayList<Candidate>(sorted.size)
        for (c in sorted) if (seen.add(c.name)) deduped.add(c)
        return deduped
    }

    // ── 成员解密（current / transitional 共用） ───────────────────
    private fun derivesMarkers(fileName: String, config: IappConfig, markerPostKey: ByteArray?): Pair<ByteArray, ByteArray> {
        val name = normalizeMemberName(fileName).toByteArray(Charsets.UTF_8)
        val sokDek = (config.sok + config.dek).toByteArray(Charsets.UTF_8)
        val dekSok = (config.dek + config.sok).toByteArray(Charsets.UTF_8)
        return IappCrypto.slky(name, sokDek, markerPostKey) to IappCrypto.slky(name, dekSok, markerPostKey)
    }

    private fun deriveMemberSeed(fileName: String, config: IappConfig, memberPostKey: ByteArray?): ByteArray {
        val nm = normalizeMemberName(fileName)
        val first = (nm + config.sok + config.dek).toByteArray(Charsets.UTF_8)
        val second = nm.toByteArray(Charsets.UTF_8)
        return IappCrypto.slky(first, second, memberPostKey)
    }

    private fun memberStrategies(ks: KeySet, preferred: MemberStrategy?): List<MemberStrategy> {
        val variants = listOf(
            MemberStrategy(ks.postKey, ks.postKey, "xor", "keyset/xor"),
            MemberStrategy(ks.postKey, ks.postKey, "direct", "keyset/direct"),
            MemberStrategy(MAGIC_STRING, MAGIC_STRING, "xor", "post/xor"),
            MemberStrategy(MAGIC_STRING, MAGIC_STRING, "direct", "post/direct"),
            MemberStrategy(null, null, "direct", "nopost/direct"),
            MemberStrategy(null, null, "xor", "nopost/xor"),
            MemberStrategy(MAGIC_STRING, null, "direct", "marker-post_key-nopost/direct"),
            MemberStrategy(MAGIC_STRING, null, "xor", "marker-post_key-nopost/xor"),
            MemberStrategy(null, MAGIC_STRING, "xor", "marker-nopost_key-post/xor"),
            MemberStrategy(ks.postKey, MAGIC_STRING, "xor", "marker-keyset_key-post/xor"),
            MemberStrategy(MAGIC_STRING, ks.postKey, "xor", "marker-post_key-keyset/xor"),
        )
        val ordered = ArrayList<MemberStrategy>()
        if (preferred != null) ordered.add(preferred)
        ordered.addAll(variants)
        val seen = HashSet<String>()
        val out = ArrayList<MemberStrategy>()
        for (m in ordered) {
            val sig = "${m.markerPostKey?.toHex()}|${m.memberPostKey?.toHex()}|${m.keyMode}"
            if (seen.add(sig)) out.add(m)
        }
        return out
    }

    private fun tryDecryptMember(container: ByteArray, fileName: String, config: IappConfig, xorKey: ByteArray, strategy: MemberStrategy): ByteArray? {
        val (startMarker, endMarker) = derivesMarkers(fileName, config, strategy.markerPostKey)
        val start = container.indexOfPattern(startMarker)
        if (start < 0) return null
        val payloadStart = start + startMarker.size
        val end = container.indexOfPattern(endMarker, payloadStart)
        if (end < 0) return null
        val blob = container.copyOfRange(payloadStart, end)
        if (blob.isEmpty() || blob.size % 16 != 0) return null
        val seed = deriveMemberSeed(fileName, config, strategy.memberPostKey)
        val memberKey = when (strategy.keyMode) {
            "xor" -> IappCrypto.cyclicXor(seed, xorKey).copyOf(16)
            "direct" -> seed.copyOf(16)
            else -> throw IllegalArgumentException("unknown key mode: ${strategy.keyMode}")
        }
        return try { IappCrypto.aesCbcThenXorDecrypt(blob, memberKey) } catch (e: Exception) { null }
    }

    private fun scanMemberReferences(content: ByteArray): Set<String> {
        val text = String(content, Charsets.UTF_8)
        val refs = HashSet<String>()
        for (m in REF_RE.findAll(text)) refs.add(normalizeMemberName(m.groupValues[1]))
        for (m in FN_RE.findAll(text)) refs.add(normalizeMemberName(m.groupValues[1] + ".myu"))
        for (m in CALL_RE.findAll(text)) {
            val ext = m.groupValues[1].trim()
            val module = m.groupValues[2].trim()
            refs.add(normalizeMemberName("$module.$ext"))
        }
        return refs.filter { it.substringAfterLast('.', "") in REF_SUFFIXES }.toHashSet()
    }

    private fun crawlMembers(container: ByteArray, config: IappConfig, ks: KeySet, preferred: MemberStrategy): Pair<LinkedHashMap<String, ByteArray>, List<String>> {
        val queue = ArrayDeque<String>()
        queue.add(normalizeMemberName(config.entryFile))
        val seen = HashSet<String>()
        val extracted = LinkedHashMap<String, ByteArray>()
        val failed = ArrayList<String>()
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!seen.add(current)) continue
            var content: ByteArray? = null
            for (st in memberStrategies(ks, preferred)) {
                content = tryDecryptMember(container, current, config, ks.xorKey, st)
                if (content != null) break
            }
            if (content == null) {
                failed.add(current); continue
            }
            extracted[current] = content
            for (ref in scanMemberReferences(content).sorted()) if (ref !in seen) queue.add(ref)
        }
        return extracted to failed
    }

    fun looksLikeIappPlain(data: ByteArray): Boolean {
        val head = data.copyOfRange(0, minOf(4096, data.size))
        val needles = listOf("<View", "</View", "<eventItme", "<UIEventset", "LinearLayout", "function", "fn ", "call(", "dim ", "syso(")
        var hits = 0
        for (n in needles) if (head.indexOfPattern(n.toByteArray(Charsets.US_ASCII)) >= 0) hits++
        var textish = 0
        for (b in head) {
            val v = b.toInt() and 0xFF
            if (v == 9 || v == 10 || v == 13 || (v in 32 until 127)) textish++
        }
        val ratio = textish.toDouble() / maxOf(1, head.size)
        return hits >= 2 || (hits >= 1 && ratio >= 0.65)
    }

    // ── legacy4 族 ───────────────────────────────────────────────
    private fun decryptLegacy4(libSo: ByteArray, config: IappConfig): Result {
        val soKey = config.sok
        val signCands = legacySignCandidates(config)
        val pwdCands = legacyPwdCandidates(config)
        var tried = 0
        for ((signSource, signValue) in signCands) for ((pwdSource, pwdValue) in pwdCands) {
            tried++
            val libKey = legacyLocalLibKey(config, signValue, pwdValue)
            val outerPlain = try { legacyTrimPlain(legacyAesCbcDecryptB64(libSo, libKey)) } catch (e: Exception) { continue }
            val outerText = String(outerPlain, Charsets.ISO_8859_1)
            if (!outerText.contains(LEGACY_LOCAL_MARKER)) continue
            val names = legacyFindSegmentNames(outerText)
            val extracted = LinkedHashMap<String, ByteArray>()
            val failed = ArrayList<String>()
            names.forEachIndexed { idx, name ->
                val content = legacyDecryptMember(outerText, soKey, idx, name)
                if (content == null) failed.add(name) else extracted[name] = content
            }
            legacyApplyFixedAliases(extracted, soKey)
            legacyApplyReferenceAliases(extracted, soKey)
            val trace = LinkedHashMap<String, String>()
            trace["algorithm_family"] = "legacy4"
            trace["candidate"] = "legacy4:local"
            trace["key_set"] = "$signSource|$pwdSource"
            trace["member_strategy"] = "md5/base64/aes-cbc"
            trace["outer_key"] = libKey
            trace["resolved_sok"] = config.sok
            trace["resolved_dek"] = config.dek
            trace["tried_count"] = tried.toString()
            trace["sign_key"] = signValue
            trace["pwd_key"] = pwdValue
            return Result(outerPlain, extracted, failed, trace)
        }
        throw RuntimeException("legacy4 decrypt failed after trying $tried candidate combinations")
    }

    private fun legacySignCandidates(config: IappConfig): List<Pair<String, String>> {
        val raw = config.signKey.trim()
        val out = ArrayList<Pair<String, String>>()
        if (raw.isNotEmpty()) {
            out.add("manual-input" to raw)
            out.add("cert-md5-derived" to IappCrypto.md5Hex(raw + "mmpygs93" + config.sok))
        }
        out.add("default-null" to "null")
        val seen = HashSet<String>()
        return out.filter { seen.add(it.second) }
    }

    private fun legacyPwdCandidates(config: IappConfig): List<Pair<String, String>> {
        val raw = config.pwdKey.trim()
        val out = ArrayList<Pair<String, String>>()
        if (raw.isNotEmpty()) {
            out.add("manual-input" to raw)
            out.add("password-derived" to IappCrypto.md5Hex(raw + "mmpfbf"))
        }
        out.add("default-empty" to "")
        val seen = HashSet<String>()
        return out.filter { seen.add(it.second) }
    }

    private fun legacyLocalLibKey(config: IappConfig, signKey: String, pwdKey: String): String {
        val appKey = IappCrypto.md5Hex(config.appName + config.versionName + config.packageName + config.versionCode)
        val half = IappCrypto.md5Hex(config.dek + config.sok + appKey + signKey + pwdKey)
        return half.substring(16, 32)
    }

    private fun legacyAesCbcDecryptB64(cipherB64: ByteArray, keyText: String): ByteArray {
        val clean = cipherB64.toString(Charsets.ISO_8859_1).replace(Regex("\\s+"), "")
        var fixed = clean
        val missing = fixed.length % 4
        if (missing != 0) fixed += "=".repeat(4 - missing)
        val ciphertext = java.util.Base64.getDecoder().decode(fixed)
        return IappCrypto.aesCbcDecrypt(ciphertext, keyText.toByteArray(Charsets.UTF_8))
    }

    private fun legacyTrimPlain(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val last = data[data.size - 1].toInt() and 0xFF
        var out = data
        if (last in 1..16) {
            var all = true
            for (i in data.size - last until data.size) if ((data[i].toInt() and 0xFF) != last) { all = false; break }
            if (all) out = data.copyOfRange(0, data.size - last)
        }
        var end = out.size
        while (end > 0 && out[end - 1].toInt() == 0) end--
        return out.copyOfRange(0, end)
    }

    private fun legacyFindSegmentNames(outerText: String): List<String> {
        val head = outerText.substringBefore(LEGACY_LOCAL_MARKER)
        val names = ArrayList<String>()
        for (item in head.split("=/")) {
            val trimmed = item.trim()
            if (trimmed.isEmpty()) continue
            val decoded = try { legacySafeB64decodeToText(trimmed) } catch (e: Exception) { null }
            if (!decoded.isNullOrEmpty()) names.add(decoded)
        }
        return names
    }

    private fun legacySafeB64decodeToText(value: String): String {
        var raw = value.replace(Regex("\\s+"), "")
        val missing = raw.length % 4
        if (missing != 0) raw += "=".repeat(4 - missing)
        return String(java.util.Base64.getDecoder().decode(raw), Charsets.UTF_8)
    }

    private fun legacyFileMarkers(soKey: String, index: Int): Pair<String, String> {
        val keyMd5 = IappCrypto.md5Hex(soKey + "iapp" + index)
        val keysMd5 = IappCrypto.md5Hex(soKey + "ysiapp" + index)
        val startMarker = java.util.Base64.getEncoder().encodeToString(keysMd5.toByteArray(Charsets.UTF_8)).replace("\n", "")
        val endMarker = java.util.Base64.getEncoder().encodeToString(keyMd5.toByteArray(Charsets.UTF_8)).replace("\n", "")
        return startMarker to endMarker
    }

    private fun legacyDecryptMember(outerText: String, soKey: String, index: Int, fileName: String): ByteArray? {
        val (startMarker, endMarker) = legacyFileMarkers(soKey, index)
        val startIdx = outerText.indexOf(startMarker)
        if (startIdx < 0) return null
        val endIdx = outerText.indexOf(endMarker, startIdx + startMarker.length)
        if (endIdx < 0) return null
        val segment = outerText.substring(startIdx + startMarker.length, endIdx)
        val keysMd5 = IappCrypto.md5Hex(soKey + "ysiapp" + index)
        val decryptKey = IappCrypto.md5Hex(soKey + keysMd5 + fileName).substring(16, 32)
        return try {
            legacyTrimPlain(legacyAesCbcDecryptB64(segment.toByteArray(Charsets.ISO_8859_1), decryptKey))
        } catch (e: Exception) { null }
    }

    private fun legacyDiscoverReferenceNames(text: String): List<String> {
        val names = ArrayList<String>()
        for (m in LEGACY_REF_RE.findAll(text)) {
            val file = m.value
            if (file.isEmpty()) continue
            val item = when {
                file.startsWith("fn ") -> file.substring(3) + ".myu"
                file.startsWith("call(") -> {
                    val module = LEGACY_CALL_MODULE_RE.find(file)?.groupValues?.get(1)
                    val quoted = Regex("\"([^\"]+)\"").find(file)?.groupValues?.get(1)
                    if (module == null || quoted == null) continue else module + quoted
                }
                else -> file
            }
            if (item !in names) names.add(item)
        }
        return names
    }

    private fun legacyApplyFixedAliases(files: LinkedHashMap<String, ByteArray>, soKey: String) {
        for (name in listOf("import.mjs", "import.mlua", "mian.iyu")) {
            val hashed = IappCrypto.md5Hex(soKey + "ysiapp" + name)
            if (hashed in files && name !in files) files[name] = files.remove(hashed)!!
        }
    }

    private fun legacyApplyReferenceAliases(files: LinkedHashMap<String, ByteArray>, soKey: String) {
        var changed = true
        while (changed) {
            changed = false
            for (content in files.values.toList()) {
                val text = String(content, Charsets.UTF_8)
                for (name in legacyDiscoverReferenceNames(text)) {
                    val hashed = IappCrypto.md5Hex(soKey + "ysiapp" + name)
                    if (hashed in files && name !in files) {
                        files[name] = files.remove(hashed)!!
                        changed = true
                    }
                }
            }
        }
    }

    // ── transitional 族 ──────────────────────────────────────────
    private fun decryptTransitional(libSo: ByteArray, config: IappConfig): Result {
        val env = buildOuterEnv(config)
        val first = envOf(env, "base")
        val seconds = listOf("version_name", "package_name", "app_name", "version_code", "sok", "dek").map { envOf(env, it) }
        val pick = idbfjPick6Unsigned(first)
        val second = seconds[pick]
        val p19 = IappCrypto.slky(first, second, null)
        val signature = envOf(env, "signature")
        val p24 = if (signature.isNotEmpty()) IappCrypto.slky(signature, p19, null) else IappCrypto.slky(p19, p19, null)
        val p25 = IappCrypto.slky(p19, p24, null)
        val outerKey = p25.copyOf(16)
        val container = IappCrypto.aesCbcDecryptOnly(libSo, outerKey)
        val queue = ArrayDeque<String>()
        queue.add(normalizeMemberName(config.entryFile))
        val seen = HashSet<String>()
        val extracted = LinkedHashMap<String, ByteArray>()
        val failed = ArrayList<String>()
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!seen.add(current)) continue
            val content = tryTransitionalMember(container, current, config)
            if (content == null) { failed.add(current); continue }
            extracted[current] = content
            for (ref in scanMemberReferences(content).sorted()) if (ref !in seen) queue.add(ref)
        }
        val trace = LinkedHashMap<String, String>()
        trace["algorithm_family"] = "transitional"
        trace["candidate"] = "transitional:unsigned:none:p19_sig_p25:direct"
        trace["outer_key"] = outerKey.toHex()
        trace["resolved_sok"] = config.sok
        trace["resolved_dek"] = config.dek
        return Result(container, extracted, failed, trace)
    }

    private fun tryTransitionalMember(container: ByteArray, fileName: String, config: IappConfig): ByteArray? {
        val name = normalizeMemberName(fileName).toByteArray(Charsets.UTF_8)
        val sokDek = (config.sok + config.dek).toByteArray(Charsets.UTF_8)
        val dekSok = (config.dek + config.sok).toByteArray(Charsets.UTF_8)
        val startMarker = IappCrypto.slky(name, sokDek, null)
        val endMarker = IappCrypto.slky(name, dekSok, null)
        val start = container.indexOfPattern(startMarker)
        if (start < 0) return null
        val payloadStart = start + startMarker.size
        val end = container.indexOfPattern(endMarker, payloadStart)
        if (end < 0) return null
        val blob = container.copyOfRange(payloadStart, end)
        if (blob.isEmpty() || blob.size % 16 != 0) return null
        val seed = IappCrypto.slky(name + sokDek, name, null)
        return try { IappCrypto.aesCbcDecryptOnly(blob, seed.copyOf(16)) } catch (e: Exception) { null }
    }
}

/** 在字节数组中查找子序列，返回首次出现的下标（找不到返回 -1）。 */
internal fun ByteArray.indexOfPattern(pattern: ByteArray, from: Int = 0): Int {
    if (pattern.isEmpty()) return from
    val max = size - pattern.size
    var i = from
    while (i <= max) {
        var j = 0
        while (j < pattern.size && this[i + j] == pattern[j]) j++
        if (j == pattern.size) return i
        i++
    }
    return -1
}
