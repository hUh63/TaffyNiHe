package com.soreverse.mcp

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.soreverse.mcp.core.runCatchingCancellable
import com.soreverse.mcp.core.splitJsonChunks
import com.soreverse.mcp.core.safeLaunch
import com.soreverse.mcp.core.EngineProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 「尾跳调用点」按需展开。
 *
 * 控制流里 `br x2` 这类**参数尾跳**的目标由调用方决定，本函数内看不出答案。
 * 这个动作去查：谁调用了本函数（`axtj`）→ 每个调用点前 20 条指令里 x2 被赋成了什么
 * （[AnalysisCfgRebuild.evalParamAtCaller]）→ 列出实际可能的目标。
 *
 * 按需触发：一次展开要 1 次 `axtj` + N 次 `pDj`（N = 调用点数，上限 24），
 * 所以不做成自动流程。
 */
internal class TailCallRow(
    val caller: Long,
    val param: String,
    val value: String,
)

@Composable
internal fun TailExpandButton(
    context: Context,
    ws: String,
    funcVa: Long,
    linear: List<Pair<Long, String>>,
    zh: Boolean,
) {
    var open by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    // 结果按目标函数（+工作区）分桶：换函数后不能把上一个函数的调用点列表继续显示。
    var rows by remember(funcVa, ws) { mutableStateOf<List<TailCallRow>>(emptyList()) }
    var message by remember(funcVa, ws) { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    IconButton(
        onClick = {
            open = true
            if (rows.isEmpty() && !loading) {
                loading = true
                message = ""
                scope.safeLaunch {
                    val r: List<TailCallRow> = withContext(Dispatchers.IO) {
                        runCatchingCancellable { analyze(context, ws, funcVa, linear) }.getOrDefault(emptyList())
                    }
                    rows = r
                    if (r.isEmpty()) {
                        message = if (zh) "没有找到调用点（可能经 GOT/PLT 间接调用，或本函数无人调用）"
                        else "No callers found (maybe called via GOT/PLT, or unreferenced)"
                    }
                    loading = false
                }
            }
        },
        enabled = linear.isNotEmpty() && funcVa > 0L,
    ) {
        Icon(Icons.Filled.SwapHoriz, contentDescription = if (zh) "尾跳调用点" else "Tail-call sites")
    }

    if (!open) return
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(if (zh) "尾跳调用点" else "Tail-call sites") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (loading) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.padding(8.dp))
                        Text(if (zh) " 正在查调用点…" else " Resolving callers…")
                    }
                } else if (rows.isEmpty()) {
                    Text(message.ifBlank { if (zh) "无结果" else "No result" })
                } else {
                    Text(
                        if (zh) "${rows.map { it.caller }.distinct().size} 个调用点 · ${rows.size} 条线索"
                        else "${rows.map { it.caller }.distinct().size} callers · ${rows.size} hints",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(rows) { r ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text(
                                    "bl 0x" + java.lang.Long.toHexString(r.caller),
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    "  ${r.param} = ${r.value}",
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { open = false }) { Text(if (zh) "关闭" else "Close") }
        },
    )
}

/** 拉调用点 + 每个调用点前 20 条指令 + 求值。 */
private fun analyze(
    context: Context,
    ws: String,
    funcVa: Long,
    linear: List<Pair<Long, String>>,
): List<TailCallRow> {
    if (ws.isBlank() || funcVa <= 0L) return emptyList()
    val jumps = AnalysisCfgRebuild.paramTailJumps(linear)
    if (jumps.isEmpty()) return emptyList()
    val eng = EngineProvider.get(context)
    val hexVa = java.lang.Long.toHexString(funcVa)

    val callers = runCatching {
        val res = eng.rzCommand(ws, "", "e scr.color=0; s $hexVa; af; axtj")
        parseCallerAddrs(textOf(res))
    }.getOrDefault(emptyList())
    if (callers.isEmpty()) return emptyList()

    val picked = callers.take(24)
    // 一次调用跑完所有调用点的前置指令：native 每条 rzCommand 都要重建 RzCore + 全量分析，
    // 24 个调用点各发一次 = 24 次分析（几十秒），合并后只有 2 次（axtj + 这批 pDj）。
    val cmds = picked.joinToString("; ") {
        // 调用点前 20 条 = 往回 80 字节（AArch64 定长 4 字节）
        "pDj 20 @ " + java.lang.Long.toHexString(maxOf(0L, it - 80L))
    }
    val chunks = runCatching {
        splitJsonChunks(textOf(eng.rzCommand(ws, "", "e scr.color=0; " + cmds)))
    }.getOrDefault(emptyList())
    // 有命令报错会让输出少一段 → 宁可逐个重查，也不让参数值错位
    val merged = chunks.size == picked.size

    val out = ArrayList<TailCallRow>()
    picked.forEachIndexed { idx, caller ->
        val ctx = runCatching {
            if (merged) {
                parseOps(chunks[idx])
            } else {
                parseOps(textOf(eng.rzCommand(ws, "", "e scr.color=0; pDj 20 @ " + java.lang.Long.toHexString(maxOf(0L, caller - 80L)))))
            }
        }.getOrDefault(emptyList())
        jumps.forEach { (_, param) ->
            val v = AnalysisCfgRebuild.evalParamAtCaller(ctx, param)
            out.add(TailCallRow(caller, param, v.ifBlank { "?" }))
        }
    }
    return out
}

/** rizin 结果取文本（native 侧 puts 到 stdout，Kotlin 侧 ok() 会摊平）。 */
private fun textOf(res: JSONObject?): String {
    if (res == null) return ""
    val s = res.optString("stdout").ifBlank { res.optString("text") }
    return if (s.isNotBlank()) s else res.toString()
}

/** `axtj` → 调用点地址（from 字段，十进制）。 */
private fun parseCallerAddrs(json: String): List<Long> {
    val t = json.trim()
    val arr = runCatching { JSONArray(t) }.getOrNull()
        ?: runCatching { JSONArray(t.substring(t.indexOf('['))) }.getOrNull()
        ?: return emptyList()
    val out = ArrayList<Long>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val from = o.optLong("from", 0L)
        if (from > 0L) out.add(from)
    }
    return out.distinct().sorted()
}

/** `pDj` → (地址, 反汇编文本)。 */
private fun parseOps(json: String): List<Pair<Long, String>> {
    val t = json.trim()
    val arr = runCatching { JSONArray(t) }.getOrNull()
        ?: runCatching { JSONArray(t.substring(t.indexOf('['))) }.getOrNull()
        ?: return emptyList()
    val out = ArrayList<Pair<Long, String>>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val off = o.optLong("offset", 0L)
        val dis = o.optString("disasm", "")
        if (off > 0L && dis.isNotBlank()) out.add(off to dis)
    }
    return out
}
