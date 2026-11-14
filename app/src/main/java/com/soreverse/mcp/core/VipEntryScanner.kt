package com.soreverse.mcp.core

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.File

/**
 * 「会员/VIP 入口」主题扫描：不做通用字符串搜索，而是带着一个明确问题去 dex 里取证——
 * **这个应用是怎么判断"我是会员"的、购买状态存在哪、失效时间怎么写**。
 *
 * 与 `taffy_search_strings` 的区别：那个是"我给你一个词，你帮我找"；这里是"我不知道该搜什么，
 * 你按会员业务的常见命名与词汇帮我列出候选，并按可信度排序"。实现上三件事：
 *
 *  1. **关键词分组加权**：会员标记 / 购买支付 / 期限试用 / 凭证 / 权限判断 / 免费限制。
 *     跨组命中（比如 `isVip` + `expireTime`）比单组更像真实业务闭环，分数更高。
 *  2. **token 化而不是裸 contains**：`payload` 不能命中 `pay`，`isVip` 必须命中 `vip`。
 *     camelCase / 下划线 / 美元符号都会拆成 token。
 *  3. **可落地**：命中结果能直接生成 Frida hook 脚本（[buildHookScript]）与 markdown 报告
 *     （[buildReport]），不是只给一堆字符串让用户自己拼。
 *
 * 只用 dexlib2 做解析，不依赖 Android、不依赖引擎，纯 JVM 可测。
 */
internal object VipEntryScanner {

    /** 一条命中。 */
    data class Finding(
        /** STRING / CLASS / METHOD / FIELD */
        val kind: String,
        /** 关键词组名（会员标记 / 购买支付 / …） */
        val category: String,
        /** 所在类（smali 描述，如 `Lcom/x/VipManager;`），字符串命中的是字面量本身所在位置为空 */
        val owner: String,
        val name: String,
        /** 方法为 `owner->name(args)ret`；其它为空 */
        val smaliSig: String,
        /** Java 可读签名，如 `boolean isVip()` */
        val javaSig: String,
        /** 命中的词 */
        val hits: List<String>,
        val score: Int,
        /**
         * 谁调用了它（smali 签名，最多 [MAX_CALLERS] 个）。
         *
         * 静态命名只能说明「这个名字像会员判断」，调用者才能说明「谁在用这个判断决定 UI」——
         * `MainActivity.onCreate` 里调的 `isVip()` 基本就是真入口，而只在单元测试里出现的不是。
         */
        val callers: List<String> = emptyList(),
    )

    data class ScanResult(
        val findings: List<Finding>,
        val dexCount: Int,
        val classCount: Int,
        val stringCount: Int,
        val elapsedMs: Long,
        val truncated: Boolean,
    )

    /** 关键词组：[weight] 越大表示这个词本身越"像"会员判断。 */
    private class Group(val name: String, val weight: Int, val words: List<String>)

    private val GROUPS = listOf(
        Group(
            "会员标记", 3,
            listOf(
                "vip", "svip", "isvip", "is_vip", "hasvip", "has_vip", "ispro", "is_pro",
                "premium", "membership", "member", "subscribed", "subscription", "entitlement", "entitled",
            ),
        ),
        Group(
            "购买支付", 3,
            listOf(
                "purchase", "purchased", "billing", "paywall", "inapp", "iap", "checkout",
                "sku", "payment", "recharge", "order", "unlock", "unlocked",
            ),
        ),
        Group(
            "期限试用", 2,
            listOf(
                "expire", "expired", "expiry", "expiration", "deadline", "validuntil", "valid_until",
                "daysleft", "days_left", "remaining", "lifetime", "trial", "freetrial", "free_trial",
            ),
        ),
        Group(
            "凭证", 3,
            listOf("receipt", "license", "licence", "activation", "activate", "serial", "voucher", "redeem", "coupon"),
        ),
        Group(
            "权限判断", 2,
            listOf("privilege", "checkvip", "check_vip", "canuse", "can_use", "quota", "restricted", "upgrade"),
        ),
        Group(
            "免费限制", 1,
            listOf("adfree", "ad_free", "noads", "no_ads", "limit", "free", "basic", "guest", "anonymous"),
        ),
    )

    /** 判断入口常见前缀：getXxx/isXxx/hasXxx/checkXxx 这类比单纯字段更值得看。 */
    private val JUDGE_PREFIX = listOf("is", "has", "can", "check", "get", "should", "need")

    /** 系统/库包直接跳过：它们的 `isFree`/`limit` 与业务无关，会淹没真正的命中。 */
    private val SKIP_PREFIX = listOf(
        "Landroid/", "Ljava/", "Ljavax/", "Lkotlin/", "Lkotlinx/", "Lorg/json/", "Lorg/intellij/",
        "Lcom/google/", "Lcom/android/", "Landroidx/", "Ldalvik/", "Lsun/", "Lj$/", "Lorg/apache/",
    )

    /** 字符串池里只在"短串"上匹配：长段落里的 vip 大概率是文案而不是标记。 */
    private const val MAX_STRING_EVIDENCE_LEN = 80

    /** 每个命中最多记多少个调用者。 */
    internal const val MAX_CALLERS = 4

    fun scan(file: File, maxFindings: Int = 300, withCallers: Boolean = true): ScanResult {
        val started = System.currentTimeMillis()
        val opcodes = Opcodes.getDefault()
        val dexFiles = loadDexFiles(file, opcodes)
        val findings = ArrayList<Finding>()
        val seen = HashSet<String>()
        var classCount = 0
        var stringCount = 0
        var truncated = false

        for ((_, dex) in dexFiles) {
            // 已达上限就整体收工：只 break 内层会让后面的 dex 白跑一遍（大 APK 多 dex 时几百毫秒）
            if (findings.size >= maxFindings) { truncated = true; break }
            runCatching {
                for (cls in dex.classes) {
                    if (findings.size >= maxFindings) { truncated = true; break }
                    val type = cls.type
                    if (isSkipped(type)) continue
                    classCount++
                    scanClass(cls, findings, seen)
                }
            }
            runCatching { stringCount += dex.stringReferences.size }
            runCatching {
                for (ref in dex.stringReferences) {
                    if (findings.size >= maxFindings) { truncated = true; break }
                    val s = runCatching { ref.string }.getOrNull() ?: continue
                    if (s.isEmpty() || s.length > MAX_STRING_EVIDENCE_LEN) continue
                    val hits = matchWords(tokens(s))
                    if (hits.isEmpty()) continue
                    val g = groupOf(hits.first())
                    val key = "STRING|$s"
                    if (!seen.add(key)) continue
                    findings.add(
                        Finding(
                            kind = "STRING",
                            category = g.name,
                            owner = "",
                            name = s,
                            smaliSig = "",
                            javaSig = "",
                            hits = hits,
                            score = scoreOf(g, hits, typeName = "", judge = false, native = false),
                        )
                    )
                }
            }
        }

        if (withCallers) runCatching { attachCallers(dexFiles, findings) }
        val sorted = findings.sortedWith(compareByDescending<Finding> { it.score }.thenBy { it.category })
        return ScanResult(
            findings = sorted,
            dexCount = dexFiles.size,
            classCount = classCount,
            stringCount = stringCount,
            elapsedMs = System.currentTimeMillis() - started,
            truncated = truncated,
        )
    }

    // ───────────────────────── 生成物 ─────────────────────────

    /**
     * 生成 Frida hook 脚本。
     *
     * 只取「方法」类命中（字段 hook 需要额外知道是静态还是实例，容易出现 read-only 之类的问题，
     * 收益不如方法），最多 40 条，按分数排序。脚本自带重载解析，避免 `C[name]` 在多重载时直接抛。
     */
    fun buildHookScript(pkg: String, findings: List<Finding>, limit: Int = 40): String {
        val targets = findings.filter { it.kind == "METHOD" && it.smaliSig.isNotBlank() }
            .sortedByDescending { it.score }
            .distinctBy { it.smaliSig }
            .take(limit)
        return buildString {
            appendLine("// 塔菲逆核 · VIP 入口 hook（由静态命中生成）")
            appendLine("// 目标: " + pkg.ifBlank { "<unknown>" })
            appendLine("// 命中: " + targets.size + " 个方法")
            targets.take(8).forEach { f ->
                if (f.callers.isNotEmpty()) {
                    appendLine("//   " + f.javaSig + "  ←  " + f.callers.first())
                }
            }
            appendLine("// 用法: frida -U -f " + pkg.ifBlank { "<pkg>" } + " -l vip_hook.js --no-pause")
            appendLine(JS_TEMPLATE_PREFIX)
            if (targets.isEmpty()) {
                appendLine("  var TARGETS = [];")
            } else {
                appendLine("  var TARGETS = [")
                targets.forEachIndexed { i, f ->
                    val comma = if (i == targets.lastIndex) "" else ","
                    appendLine("    \"" + f.smaliSig + "\"" + comma)
                }
                appendLine("  ];")
            }
            appendLine(JS_TEMPLATE_SUFFIX)
        }
    }

    /** 生成 markdown 报告：按类别分组，每条带分数与签名。 */
    fun buildReport(pkg: String, result: ScanResult): String = buildString {
        appendLine("# VIP / 会员入口分析报告")
        appendLine()
        appendLine("- 目标: `" + pkg.ifBlank { "<unknown>" } + "`")
        appendLine("- DEX: " + result.dexCount + " 个，类 " + result.classCount + " 个，字符串 " + result.stringCount + " 条")
        appendLine("- 命中: " + result.findings.size + " 条" + if (result.truncated) "（已达上限，结果被截断）" else "")
        appendLine("- 耗时: " + result.elapsedMs + " ms")
        appendLine()
        val byCat = result.findings.groupBy { it.category }
        for ((cat, list) in byCat) {
            appendLine("## " + cat + "（" + list.size + "）")
            appendLine()
            appendLine("| 分数 | 类型 | 所在类/内容 | 名称 | 签名 | 命中词 | 被谁调用 |")
            appendLine("|---|---|---|---|---|---|---|")
            for (f in list.take(80)) {
                appendLine(
                    "| " + f.score + " | " + f.kind + " | `" + f.owner.ifBlank { "-" } + "` | `" +
                        escapeCell(f.name) + "` | `" + escapeCell(f.javaSig.ifBlank { "-" }) + "` | " +
                        f.hits.joinToString(" ") + " | " +
                        (if (f.callers.isEmpty()) "-" else f.callers.joinToString("<br>") { "`" + escapeCell(it) + "`" }) + " |"
                )
            }
            appendLine()
        }
        appendLine("> 说明：分数由「关键词组权重 + 跨组命中 + 命名形态（is/has/check 前缀）+ native」累加，")
        appendLine("> 只是**候选排序**，不代表一定是会员判断入口；请结合交叉引用与运行时 hook 验证。")
    }

    // ───────────────────────── 内部 ─────────────────────────

    private fun loadDexFiles(file: File, opcodes: Opcodes): List<Pair<String, DexBackedDexFile>> {
        val name = file.name.lowercase()
        return if (name.endsWith(".apk") || name.endsWith(".zip")) {
            val container = DexFileFactory.loadDexContainer(file, opcodes)
            container.dexEntryNames.mapNotNull { n ->
                runCatching { container.getEntry(n)?.dexFile }.getOrNull()?.let { n to it }
            }
        } else {
            listOf(file.name to DexFileFactory.loadDexFile(file, opcodes))
        }
    }

    /**
     * 给方法类命中补「调用者」。
     *
     * 需要再遍历一遍全量指令：dexlib2 没有反向索引，只能把所有方法体过一遍、看哪些指令引用了目标签名。
     * 因此这一步是可选的（`withCallers`），且在结果里明确只保留前几个调用者——目的是给出
     * 「谁在用它」的方向感，不是做完整调用图（那该用 [com.soreverse.mcp.core.VipEntryScanner] 之外的引擎）。
     */
    private fun attachCallers(
        dexFiles: List<Pair<String, DexBackedDexFile>>,
        findings: MutableList<Finding>,
    ) {
        val index = HashMap<String, Int>()
        findings.forEachIndexed { i, f ->
            if (f.kind == "METHOD" && f.smaliSig.isNotBlank()) index[f.smaliSig] = i
        }
        if (index.isEmpty()) return
        val callers = HashMap<Int, LinkedHashSet<String>>()
        for ((_, dex) in dexFiles) {
            runCatching {
                for (cls in dex.classes) {
                    if (isSkipped(cls.type)) continue
                    for (m in cls.methods) {
                        val impl = runCatching { m.implementation }.getOrNull() ?: continue
                        val callerSig = runCatching {
                            methodSmaliSig(
                                m.definingClass, m.name,
                                m.parameterTypes.map { it.toString() }, m.returnType.toString(),
                            )
                        }.getOrNull() ?: continue
                        for (ins in impl.instructions) {
                            if (ins !is ReferenceInstruction) continue
                            val ref = runCatching { ins.reference }.getOrNull() as? MethodReference ?: continue
                            val sig = runCatching {
                                methodSmaliSig(
                                    ref.definingClass, ref.name,
                                    ref.parameterTypes.map { it.toString() }, ref.returnType.toString(),
                                )
                            }.getOrNull() ?: continue
                            val i = index[sig] ?: continue
                            val set = callers.getOrPut(i) { LinkedHashSet() }
                            if (set.size < MAX_CALLERS) set.add(callerSig)
                        }
                    }
                }
            }
        }
        if (callers.isEmpty()) return
        for ((i, set) in callers) findings[i] = findings[i].copy(callers = set.toList())
    }

    private fun scanClass(cls: ClassDef, out: MutableList<Finding>, seen: MutableSet<String>) {
        val owner = cls.type
        val ownerTokens = tokens(owner)
        val ownerHits = matchWords(ownerTokens)
        if (ownerHits.isNotEmpty()) {
            val g = groupOf(ownerHits.first())
            if (seen.add("CLASS|$owner")) {
                out.add(
                    Finding(
                        kind = "CLASS",
                        category = g.name,
                        owner = owner,
                        name = smaliToJavaType(owner),
                        smaliSig = "",
                        javaSig = "",
                        hits = ownerHits,
                        score = scoreOf(g, ownerHits, typeName = owner, judge = false, native = false) + 2,
                    )
                )
            }
        }
        for (m in cls.methods) {
            val tokens = tokens(m.name)
            val hits = matchWords(tokens)
            if (hits.isEmpty()) continue
            val g = groupOf(hits.first())
            // parameterTypes/returnType 在 dexlib2 里是 CharSequence 族，统一转成 String 再拼签名
            val params = m.parameterTypes.map { it.toString() }
            val ret = m.returnType.toString()
            val sig = methodSmaliSig(m.definingClass, m.name, params, ret)
            if (!seen.add("METHOD|$sig")) continue
            val native = (m.accessFlags and AccessFlags.NATIVE.value) != 0
            out.add(
                Finding(
                    kind = "METHOD",
                    category = g.name,
                    owner = m.definingClass,
                    name = m.name,
                    smaliSig = sig,
                    javaSig = methodJavaSig(m.name, params, ret),
                    hits = hits,
                    score = scoreOf(g, hits, typeName = owner, judge = isJudgeName(m.name), native = native),
                )
            )
        }
        for (f in cls.fields) {
            val hits = matchWords(tokens(f.name))
            if (hits.isEmpty()) continue
            val g = groupOf(hits.first())
            val key = "FIELD|${f.definingClass}->${f.name}"
            if (!seen.add(key)) continue
            out.add(
                Finding(
                    kind = "FIELD",
                    category = g.name,
                    owner = f.definingClass,
                    name = f.name,
                    smaliSig = "",
                    javaSig = f.type.toString(),
                    hits = hits,
                    score = scoreOf(g, hits, typeName = owner, judge = false, native = false) - 1,
                )
            )
        }
    }

    private fun scoreOf(g: Group, hits: List<String>, typeName: String, judge: Boolean, native: Boolean): Int {
        var score = g.weight
        // 跨组命中：一条名字同时蹭到"会员标记"和"期限"，往往就是真实入口
        val categories = hits.map { groupOf(it).name }.distinct().size
        if (categories > 1) score += 2
        if (judge) score += 2
        if (native) score += 1
        if (typeName.isNotBlank() && matchWords(tokens(typeName)).isNotEmpty()) score += 1
        return score
    }

    private fun isJudgeName(name: String): Boolean {
        val first = tokens(name).firstOrNull() ?: return false
        return first in JUDGE_PREFIX
    }

    /** camelCase / 下划线 / 美元符 / smali 分隔符都拆成小写 token。 */
    internal fun tokens(raw: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var prevLower = false
        for (c in raw) {
            when {
                c.isLetterOrDigit() -> {
                    if (c.isUpperCase() && prevLower && sb.isNotEmpty()) {
                        out.add(sb.toString().lowercase()); sb.setLength(0)
                    }
                    sb.append(c)
                    prevLower = c.isLowerCase() || c.isDigit()
                }
                else -> {
                    if (sb.isNotEmpty()) { out.add(sb.toString().lowercase()); sb.setLength(0) }
                    prevLower = false
                }
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString().lowercase())
        return out
    }

    /** 返回命中的关键词（最多 4 个）。 */
    internal fun matchWords(tokens: List<String>): List<String> {
        if (tokens.isEmpty()) return emptyList()
        val hits = ArrayList<String>()
        for (tk in tokens) {
            for (g in GROUPS) {
                for (w in g.words) {
                    if (tk == w || tk.startsWith(w) && w.length >= 5) {
                        if (!hits.contains(w)) hits.add(w)
                    }
                }
                if (hits.size >= 4) return hits
            }
        }
        return hits
    }

    private fun groupOf(word: String): Group =
        GROUPS.firstOrNull { g -> g.words.any { it == word } } ?: GROUPS.last()

    private fun isSkipped(type: String): Boolean = SKIP_PREFIX.any { type.startsWith(it) }

    internal fun smaliToJavaType(desc: String): String {
        var i = 0
        var dims = 0
        while (i < desc.length && desc[i] == '[') { dims++; i++ }
        val base = if (i < desc.length) {
            when (desc[i]) {
                'L' -> desc.substring(i + 1, desc.length - 1).replace('/', '.')
                'V' -> "void"; 'Z' -> "boolean"; 'B' -> "byte"; 'S' -> "short"; 'C' -> "char"
                'I' -> "int"; 'J' -> "long"; 'F' -> "float"; 'D' -> "double"
                else -> desc.substring(i)
            }
        } else ""
        return base + "[]".repeat(dims)
    }

    private fun methodSmaliSig(owner: String, name: String, params: List<String>, ret: String): String =
        owner + "->" + name + "(" + params.joinToString("") + ")" + ret

    private fun methodJavaSig(name: String, params: List<String>, ret: String): String =
        smaliToJavaType(ret) + " " + name + "(" + params.joinToString(", ") { smaliToJavaType(it) } + ")"

    private fun escapeCell(s: String): String = s.replace("|", "\\|")

    /** JS 模板前半段：解析 smali 签名 + 重载定位。刻意不用 `$`，避免与 Kotlin 字符串模板冲突。 */
    private val JS_TEMPLATE_PREFIX = """
Java.perform(function () {
  function parse(sig) {
    var i = sig.indexOf('->');
    if (i < 0) return null;
    var cls = sig.substring(1, i).replace(/\//g, '.');
    var rest = sig.substring(i + 2);
    var p = rest.indexOf('(');
    if (p < 0) return null;
    var name = rest.substring(0, p);
    var args = rest.substring(p + 1, rest.lastIndexOf(')'));
    var map = {V:'void',Z:'boolean',B:'byte',S:'short',C:'char',I:'int',J:'long',F:'float',D:'double'};
    var js = [], cur = '', k = 0;
    while (k < args.length) {
      var c = args.charAt(k);
      if (c === '[') { cur += c; k++; continue; }
      if (c === 'L') {
        var e = args.indexOf(';', k);
        if (e < 0) break;
        cur += args.substring(k + 1, e).replace(/\//g, '.');
        k = e + 1;
      } else {
        cur += (map[c] || c);
        k++;
      }
      js.push(cur); cur = '';
    }
    return {cls: cls, name: name, args: js};
  }
""".trimIndent()

    private val JS_TEMPLATE_SUFFIX = """
  TARGETS.forEach(function (sig) {
    try {
      var d = parse(sig);
      if (!d) { console.log('[-] bad sig ' + sig); return; }
      var C = Java.use(d.cls);
      var target = d.args.length ? C[d.name].overload.apply(C[d.name], d.args) : C[d.name];
      var orig = target.implementation;
      target.implementation = function () {
        var ret = orig.apply(this, arguments);
        console.log('[VIP] ' + d.cls + '.' + d.name + ' -> ' + ret);
        return ret;
      };
      console.log('[+] hooked ' + d.cls + '.' + d.name);
    } catch (e) {
      console.log('[-] hook failed ' + sig + ' : ' + e);
    }
  });
});
""".trimIndent()
}
