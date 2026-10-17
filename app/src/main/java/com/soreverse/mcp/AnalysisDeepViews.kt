package com.soreverse.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.soreverse.mcp.core.EngineProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 分析页三个「静态深挖」专属视图: JNI 注册表 / 密文恢复 / 混淆度扫描。
 *
 * 共同设计取舍（与塔菲分析页其余工具页一致）:
 *  - 图就是图、表就是表: 不做「原始 JSON」兜底出口, 每个视图只按自己的数据形状渲染;
 *  - 空态与报错在页内就地说明「为什么没有」以及「下一步点哪个按钮」, 不跳走;
 *  - 全部用 Material 3 官方组件（Card / AssistChip / OutlinedButton / ListItem 语义）,
 *    不自绘交互控件。
 */

// ───────────────────────── 公共小件 ─────────────────────────

/** 顶部统计条: 每个 (标签, 值) 一格。 */
@Composable
private fun DeepStatStrip(items: List<Pair<String, String>>) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { (label, value) ->
                Column {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Text(
                        value,
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 页内提示（空态 / 失败 / 边界说明），不跳页。 */
@Composable
private fun DeepNotice(title: String, detail: String, tone: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = tone,
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeepBusy() {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.size(10.dp))
        Text(
            "静态分析中…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 调工具（与本包 callMcpTool 同实现；文件名级 private，不跨文件引用）。 */
private suspend fun deepTool(context: android.content.Context, name: String, args: JSONObject): JSONObject? =
    withContext(Dispatchers.IO) {
        runCatching {
            val h = com.soreverse.mcp.mcp.ToolCatalog.byName[name] ?: return@runCatching null
            val tcx = com.soreverse.mcp.mcp.ToolContext(
                context,
                com.soreverse.mcp.core.SettingsStore(context),
                EngineProvider.get(context),
                null,
            )
            h.handle(tcx, args)
        }.getOrNull()
    }

private fun deepOk(o: JSONObject?): Boolean = o != null && o.optBoolean("ok", false)

private fun deepError(o: JSONObject?): String =
    o?.let { it.optJSONObject("error")?.optString("message").orEmpty().ifBlank { it.optString("error") } }.orEmpty()

// ───────────────────────── 2. 密文恢复 ─────────────────────────

@Composable
internal fun StringCipherView(workspaceId: String, zh: Boolean, context: android.content.Context) {
    val scope = rememberCoroutineScope()
    var scanJson by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<JSONObject?>(null) }
    var detailJson by remember { mutableStateOf("") }
    var detailBusy by remember { mutableStateOf(false) }

    val scanObj = remember(scanJson) { runCatching { JSONObject(scanJson) }.getOrNull() }
    val blobs = remember(scanObj) { scanObj?.optJSONArray("blobs") ?: JSONArray() }
    val detail = remember(detailJson) { runCatching { JSONObject(detailJson) }.getOrNull() }

    fun scan() {
        if (busy) return
        busy = true
        err = ""
        picked = null
        detailJson = ""
        scope.launch {
            val r = deepTool(context, "taffy_so_string_decrypt", JSONObject().put("workspaceId", workspaceId).put("action", "scan").put("limit", 60))
            busy = false
            if (deepOk(r)) scanJson = r.toString() else err = deepError(r).ifBlank { "scan failed" }
        }
    }

    fun decrypt(blob: JSONObject) {
        picked = blob
        detailBusy = true
        detailJson = ""
        scope.launch {
            val r = deepTool(
                context,
                "taffy_so_string_decrypt",
                JSONObject()
                    .put("workspaceId", workspaceId)
                    .put("action", "decrypt")
                    .put("locator", blob.optString("va"))
                    .put("length", blob.optInt("length", 32)),
            )
            detailBusy = false
            detailJson = r?.toString() ?: ""
        }
    }

    fun copy(text: String) {
        runCatching {
            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("plaintext", text))
        }
    }

    LaunchedEffect(workspaceId) {
        if (workspaceId.isNotBlank() && scanJson.isBlank()) scan()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { scan() }, enabled = !busy && workspaceId.isNotBlank()) {
                Text(if (zh) "重新扫描" else "Rescan")
            }
            Text(
                if (zh) "只扫 .data/.rodata 里既非文本也非零填充的段；解密结果按「像不像语言」排名" else
                    "scans non-text non-zero runs in .data/.rodata; ranked by language-likeness",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 2,
            )
        }

        when {
            workspaceId.isBlank() -> DeepNotice(if (zh) "还没有打开文件" else "No file opened", if (zh) "先在顶栏选择 SO。" else "Pick a shared object first.")
            busy && scanObj == null -> DeepBusy()
            err.isNotBlank() -> DeepNotice(if (zh) "扫描失败" else "Scan failed", err, MaterialTheme.colorScheme.error)
            scanObj == null -> DeepNotice(if (zh) "尚未扫描" else "Not scanned", if (zh) "点「重新扫描」开始。" else "Tap Rescan.")
            else -> {
                DeepStatStrip(
                    listOf(
                        (if (zh) "候选段" else "blobs") to blobs.length().toString(),
                        (if (zh) "已选" else "picked") to (picked?.optString("va") ?: "—"),
                    )
                )
                Spacer(Modifier.height(8.dp))
                if (blobs.length() == 0) {
                    DeepNotice(
                        if (zh) "没有可疑密文段" else "No ciphertext runs",
                        if (zh) "说明该 SO 的字符串可能未加密，或加密后仍保持可打印（如简单 Base64 表）。" +
                            "可直接在「解码」页签按 Base64/Hex 试解。" else
                            "Strings may be unencrypted or still printable (e.g. Base64). Try the Decode tab.",
                    )
                } else {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LazyColumn(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(count = blobs.length()) { i ->
                                val b = blobs.optJSONObject(i) ?: return@items
                                val selected = picked?.optString("va") == b.optString("va")
                                Card(
                                    onClick = { decrypt(b) },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
                                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    ),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                b.optString("va"),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace,
                                                modifier = Modifier.weight(1f),
                                            )
                                            Text(
                                                "${b.optInt("length")}B · E${String.format("%.2f", b.optDouble("entropy"))}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Text(
                                            b.optString("section"),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.tertiary,
                                        )
                                        Text(
                                            b.optString("headHex"),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                        Column(Modifier.weight(1.1f)) {
                            when {
                                detailBusy -> DeepBusy()
                                detail == null -> DeepNotice(
                                    if (zh) "选一段看结果" else "Pick a blob",
                                    if (zh) "左栏点任意一段，右侧会给出 256 个单字节变换 + 最多 8 个排名候选。" else
                                        "Tap a blob on the left.",
                                )
                                else -> CipherCandidates(detail, zh) { copy(it) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CipherCandidates(detail: JSONObject, zh: Boolean, onCopy: (String) -> Unit) {
    val cands = detail.optJSONArray("candidates") ?: JSONArray()
    val confidence = detail.optString("confidence", "none")
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DeepStatStrip(
            listOf(
                (if (zh) "置信度" else "confidence") to confidence.uppercase(),
                (if (zh) "候选" else "candidates") to cands.length().toString(),
                (if (zh) "采样" else "sampled") to "${detail.optInt("sampledBytes")}B",
            )
        )
        if (cands.length() == 0) {
            DeepNotice(
                if (zh) "没有候选通过闸门" else "No candidate cleared the gate",
                if (zh) "可打印率/语言相似度都未达标，说明不是单字节或已知密钥变换。" +
                    "点「解密提示」了解该走哪条路（动态读内存 / unidbg）。" else
                    "Neither printable nor textual ratio passed; not a single-byte transform.",
                MaterialTheme.colorScheme.error,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(count = cands.length()) { i ->
                    val c = cands.optJSONObject(i) ?: return@items
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    c.optString("algorithm"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "文本 ${String.format("%.2f", c.optDouble("textualRatio"))} · 可打印 ${String.format("%.2f", c.optDouble("printableRatio"))}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                c.optString("text"),
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                c.optString("keyHex").ifBlank { "—" },
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (c.optString("text").isNotBlank()) {
                                Text(
                                    if (zh) "复制明文" else "Copy plaintext",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────── 3. 混淆度扫描 ─────────────────────────

private class ObfRow(
    val symbol: String,
    val va: String,
    val verdict: String,
    val flattening: Double,
    val bogus: Double,
    val blocks: Int,
    val dispatchers: Int,
)

@Composable
internal fun ObfuscScanView(workspaceId: String, zh: Boolean, context: android.content.Context) {
    val scope = rememberCoroutineScope()
    var json by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    var limit by remember { mutableStateOf(40) }
    val obj = remember(json) { runCatching { JSONObject(json) }.getOrNull() }

    fun scan(to: Int) {
        if (busy) return
        busy = true
        err = ""
        scope.launch {
            val r = deepTool(
                context,
                "taffy_so_obfusc_scan",
                JSONObject().put("workspaceId", workspaceId).put("limit", to),
            )
            busy = false
            if (deepOk(r)) json = r.toString() else err = deepError(r).ifBlank { "scan failed" }
        }
    }

    val rows = remember(obj) {
        val out = ArrayList<ObfRow>()
        val arr = obj?.optJSONArray("functions") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            out.add(
                ObfRow(
                    symbol = f.optString("symbol"),
                    va = f.optString("va"),
                    verdict = f.optString("verdict"),
                    flattening = f.optDouble("flatteningFactor"),
                    bogus = f.optDouble("bogusEdgeRatio"),
                    blocks = f.optInt("blockCount"),
                    dispatchers = f.optInt("dispatcherBlocks"),
                )
            )
        }
        out
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { scan(limit) }, enabled = !busy && workspaceId.isNotBlank()) {
                Text(if (zh) "扫描 $limit 个函数" else "Scan $limit")
            }
            OutlinedButton(
                onClick = { limit = if (limit >= 120) 40 else limit + 40; scan(limit) },
                enabled = !busy && workspaceId.isNotBlank(),
            ) {
                Text(if (zh) "扩大范围" else "Widen")
            }
            Text(
                if (zh) "按体积取最大的 N 个函数，看 CFG 形态；不改二进制" else "largest N functions by size, CFG shape only",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 2,
            )
        }

        when {
            workspaceId.isBlank() -> DeepNotice(if (zh) "还没有打开文件" else "No file opened", if (zh) "先在顶栏选择 SO。" else "Pick a shared object first.")
            busy && obj == null -> DeepBusy()
            err.isNotBlank() -> DeepNotice(if (zh) "扫描失败" else "Scan failed", err, MaterialTheme.colorScheme.error)
            obj == null -> DeepNotice(
                if (zh) "尚未扫描" else "Not scanned",
                if (zh) "点「扫描」开始。每个函数要跑一次 CFG 分析，40 个函数通常几秒。" else "Tap Scan; one CFG analysis per function.",
            )
            else -> {
                val flattened = obj.optInt("flattenedFunctions")
                val suspected = obj.optInt("suspectedFunctions")
                DeepStatStrip(
                    listOf(
                        (if (zh) "结论" else "overall") to obj.optString("overall").uppercase(),
                        (if (zh) "已分析" else "analyzed") to obj.optInt("analyzed").toString(),
                        (if (zh) "平坦化" else "flattened") to flattened.toString(),
                        (if (zh) "疑似" else "suspected") to suspected.toString(),
                    )
                )
                Spacer(Modifier.height(8.dp))
                if (rows.isEmpty()) {
                    DeepNotice(
                        if (zh) "没有可分析的函数" else "No analysable functions",
                        if (zh) "符号表里没有带大小的函数（stripped 或只有 dynsym 桩）。可先在「符号」页签跑一次分析。" else
                            "No sized symbols; run analysis first.",
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(rows) { r ->
                            val color = when (r.verdict) {
                                "flattened" -> MaterialTheme.colorScheme.error
                                "suspected" -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            r.symbol.ifBlank { r.va },
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            r.verdict.uppercase(),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = color,
                                        )
                                    }
                                    Text(
                                        "平坦化 ${String.format("%.2f", r.flattening)} · 假边 ${String.format("%.2f", r.bogus)} · " +
                                            "${r.blocks} 块 / ${r.dispatchers} 分发器",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────── 加固报告: 字符串特征分类（纯函数，供 HardeningView 调用） ─────────────────────────
// 从 AnalysisWorkspace.kt 迁出：该文件已超过 4000 行提醒线并登记在架构门禁基线里，
// 新增能力不得再让它增长，纯逻辑因此就近放到视图文件。

internal data class HardFinding(val category: String, val title: String, val detail: String, val severity: String)

/** 壳 / 加固厂商特征（文件名/字符串级）。 */
internal val packerSignatures: List<Triple<String, String, String>> = listOf(
    Triple("libjiagu", "360 加固保", "high"),
    Triple("libmobisec", "阿里聚安全", "high"),
    Triple("libsgmain", "阿里 SecurityGuard", "high"),
    Triple("libsgsecuritybody", "阿里 SecurityGuard", "high"),
    Triple("libDexHelper", "梆梆企业版", "high"),
    Triple("libijiami", "爱加密", "high"),
    Triple("libexecmain", "爱加密", "high"),
    Triple("libshella", "通用加固壳", "medium"),
    Triple("libshellx", "通用加固壳", "medium"),
    Triple("libnesec", "网易易盾", "high"),
    Triple("libSecShell", "娜迦", "high"),
    Triple("libsecexe", "娜迦", "high"),
    Triple("libsecmain", "娜迦", "high"),
    Triple("libtprt", "腾讯乐固/梆梆", "high"),
    Triple("libreloc", "腾讯乐固", "medium"),
    Triple("libbaiduprotect", "百度加固", "high"),
    Triple("libbprotect", "百度加固", "high"),
    Triple("libkwscmm", "几维安全", "medium"),
    Triple("libkwscr", "几维安全", "medium"),
    Triple("libVMProtect", "VMProtect", "high"),
    Triple("libedog", "易遁加固", "medium"),
)

/** 运行环境检测 / 混淆标记特征。 */
internal val envSignatures: List<Pair<String, String>> = listOf(
    "frida" to "Frida 检测（反调试/反注入）",
    "gum-js-loop" to "Frida 线程检测",
    "re.frida.server" to "Frida server 检测",
    "xposed" to "Xposed 检测",
    "substrate" to "Substrate 检测",
    "magisk" to "Magisk / Root 检测",
    "/system/bin/su" to "Root 检测（su 路径）",
    "/system/xbin/su" to "Root 检测（su 路径）",
    "goldfish" to "模拟器检测（goldfish）",
    "qemu" to "模拟器检测（qemu）",
    "ranchu" to "模拟器检测（ranchu）",
    "/proc/self/status" to "反调试（读取 TracerPid）",
    "TracerPid" to "反调试（TracerPid）",
    "ptrace" to "反调试（ptrace）",
    "ollvm" to "OLLVM 混淆标记",
    "__vmp" to "VMP 虚拟化保护标记",
    "VMHandler" to "VMP Handler 标记",
    "soinfo" to "自定义 Linker（soinfo）",
    "linker_init" to "自定义 Linker（linker_init）",
    "__dl_" to "自定义 Linker（__dl_ 符号）",
)

/** 基于字符串集合做特征匹配。 */
internal fun hardMatch(strings: List<String>): List<HardFinding> {
    val out = mutableListOf<HardFinding>()
    packerSignatures.forEach { (sig, name, sev) ->
        val ev = strings.firstOrNull { it.contains(sig, true) }
        if (ev != null) out += HardFinding("加固壳", name, "命中特征「$sig」：${ev.take(160)}", sev)
    }
    envSignatures.forEach { (sig, desc) ->
        val ev = strings.firstOrNull { it.contains(sig, true) }
        if (ev != null) {
            val sev = if (desc.contains("Frida") || desc.contains("反调试")) "high" else "medium"
            out += HardFinding("反调试/环境", desc, "命中「$sig」：${ev.take(160)}", sev)
        }
    }
    if (strings.any { it.matches(Regex("^sub_[0-9A-Fa-f]{4,}$")) }) {
        out += HardFinding("符号剥离", "符号剥离 (sub_ 命名)", "大量函数名为 sub_xxxx，符号表可能被剥离。", "low")
    }
    return out
}

/** Shannon 熵（比特/字节）。 */
internal fun shannonEntropy(bytes: ByteArray): Double {
    if (bytes.isEmpty()) return 0.0
    val freq = IntArray(256)
    bytes.forEach { freq[it.toInt() and 0xff]++ }
    var h = 0.0
    val n = bytes.size.toDouble()
    freq.forEach { c -> if (c > 0) { val p = c / n; h -= p * kotlin.math.log2(p) } }
    return h
}

/** 加固结论（对标 Exbin HardeningReport.severityLabel）。 */
internal fun hardVerdict(findings: List<HardFinding>, entropy: Double, zh: Boolean): String {
    val sev = when {
        findings.any { it.severity == "high" } || entropy >= 7.0 -> 3
        findings.any { it.severity == "medium" } -> 2
        findings.any { it.severity == "low" } -> 1
        else -> 0
    }
    return when (sev) {
        3 -> if (zh) "疑似强加固" else "likely heavy hardening"
        2 -> if (zh) "疑似混淆 / 加壳" else "likely obfuscated / packed"
        1 -> if (zh) "存在异常" else "anomalies found"
        else -> if (zh) "未见明显加固" else "no obvious hardening"
    }
}

/** 结构完整性评分 100=未被破坏（对标 Exbin HardeningReport.integrityScore）。 */
internal fun hardIntegrity(findings: List<HardFinding>, entropy: Double): Int {
    var score = 100
    findings.forEach { score -= when (it.severity) { "high" -> 22; "medium" -> 10; "low" -> 4; else -> 0 } }
    if (entropy >= 7.0) score -= 25
    return score.coerceIn(0, 100)
}

/** 推荐处理流程（对标 Exbin HardeningAnalyzer.buildRecommendations）。 */
internal fun hardSteps(packed: Boolean, zh: Boolean): List<String> = when {
    !packed -> listOf(if (zh) "当前未发现强加固特征，可直接进行静态反编译。" else "No heavy hardening; static decompilation is fine.")
    zh -> listOf(
        "1) 内存 Dump：root 后用 Frida 脚本枚举模块并 dump 解密后的内存镜像。",
        "2) 修复 ELF：用 SoFixer / ELF 重建工具恢复被抹除的 Section Headers、.dynsym、.dynstr、.rela.dyn。",
        "3) 反混淆：对高跳转密度函数做控制流平坦化还原（不透明谓词 + 调度器状态机重建）。",
        "4) 动态辅助：对无法静态还原的函数，用 Unidbg / Frida Stalker 记录实时执行路径。",
        "5) 人工复核：VMP 字节码语义还原需结合 Handler 映射，建议标记为需人工介入。",
    )
    else -> listOf(
        "1) Memory dump: use a Frida script to dump the decrypted image.",
        "2) Repair ELF (SoFixer): rebuild section headers, .dynsym, .dynstr, .rela.dyn.",
        "3) De-obfuscate control-flow flattening on high-branch-density functions.",
        "4) Dynamic assist (Unidbg / Frida Stalker) for un-recoverable functions.",
        "5) Manual review for VMP bytecode (needs handler mapping).",
    )
}

// ───────────────────────── 加固报告: 结构层证据（自管数据，HardeningView 只放一行调用） ─────────────────────────

/**
 * ELF 结构层的加固指纹与反调试点定位。
 *
 * 与 HardeningView 上半部分的「字符串特征」互补：字符串能看出「像某家壳」，但看不出
 * 「哪个段名异常、导入组合像谁、反调试点在哪条路径」。这里补的正是后者，且两条数据都来自
 * 纯静态 ELF 分析，不执行目标代码。
 *
 * 数据自己拉、自己渲染，这样宿主页面不增长、生命周期也更内聚（页面滚到哪都只加载一次）。
 */
@Composable
internal fun StructuralEvidenceSection(workspaceId: String, zh: Boolean, context: android.content.Context) {
    var packerJson by remember(workspaceId) { mutableStateOf("") }
    var antiJson by remember(workspaceId) { mutableStateOf("") }
    var busy by remember(workspaceId) { mutableStateOf(false) }

    LaunchedEffect(workspaceId) {
        if (workspaceId.isBlank()) return@LaunchedEffect
        busy = true
        packerJson = runCatching {
            deepTool(context, "taffy_so_packer_fingerprint", JSONObject().put("workspaceId", workspaceId))?.toString().orEmpty()
        }.getOrDefault("")
        antiJson = runCatching {
            deepTool(context, "taffy_so_anti_debug", JSONObject().put("workspaceId", workspaceId))?.toString().orEmpty()
        }.getOrDefault("")
        busy = false
    }

    val packers = remember(packerJson) {
        runCatching { JSONObject(packerJson).optJSONArray("packers") }.getOrNull()
    }
    val antiFindings = remember(antiJson) {
        runCatching { JSONObject(antiJson).optJSONArray("findings") }.getOrNull()
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (zh) "结构层判定（只看 ELF 段名 / 符号 / 导入，不看字符串）" else "Structural verdict (ELF only)",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                workspaceId.isBlank() -> Text(
                    if (zh) "先打开文件" else "open a file first",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        if (zh) "结构层分析中…" else "analysing structure…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                packers == null || packers.length() == 0 -> Text(
                    if (zh) "结构层未命中已知加固厂商：段名、符号命名风格、导入组合都符合常规构建产物。" +
                        "若字符串层仍报强加固，多半是纯代码虚拟化（VMP），结构上看不出厂商。" else
                        "No structural packer match.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    for (i in 0 until packers.length()) {
                        val p = packers.optJSONObject(i) ?: continue
                        Text(
                            "${p.optString("name")} · ${p.optString("vendor")} · ${p.optString("confidence")} (${p.optInt("score")})",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        val ev = p.optJSONArray("evidence")
                        if (ev != null) {
                            for (j in 0 until minOf(ev.length(), 3)) {
                                Text(
                                    "· " + ev.optString(j),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            val antiN = antiFindings?.length() ?: 0
            if (antiN > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    if (zh) "反调试点 $antiN 条（导入符号 / 字符串常量 / hook 跳板）" else "anti-debug sites: $antiN",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (i in 0 until minOf(antiN, 5)) {
                    val f = antiFindings?.optJSONObject(i) ?: continue
                    Text(
                        "· ${f.optString("category")} · ${f.optString("technique")} — ${f.optString("evidence").take(120)}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
