package com.soreverse.mcp.mcp

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * 塔菲逆核: DEX 方法调用图（call graph）。
 *
 * 逆向到某个可疑方法后, 最常问的两个问题:
 *   1. 它是被谁调用的?  (callers —— 往上游追入口/触发点)
 *   2. 它调用了什么?    (callees —— 往下游看实现/加密/网络)
 * 本工具用 dexlib2 解析 APK/DEX 里所有 invoke-* 指令建立调用关系,
 * 从指定方法出发做定向 BFS, 返回调用链(带层号), 并标注哪些目标方法在 DEX 内有实现。
 *
 * 与 taffy_string_xref 互补: 后者是「字符串 -> 引用它的方法」, 本工具是「方法 <-> 方法」。
 * 纯 Java(dexlib2), 无需 native 引擎, 只读。
 */
object CallGraphTool {

    val tool: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_call_graph",
            "【方法调用图】输入方法(类名或 类->方法), 生成调用关系: direction=callers 往上追「谁调用它」, " +
                "callees 往下看「它调用谁」, both 两者都要。BFS 展开, 带层号与「是否有实现」标注。" +
                "支持 APK 或 .dex, 纯 dexlib2, 无需引擎, 只读。与 taffy_string_xref/taffy_dex_xref 互补。",
            "Method call graph (DEX). Given a method, walk callers (who calls it) / callees (what it calls) via " +
                "dexlib2 over all invoke-* instructions, BFS-expanded with depth and has-impl flag. " +
                "Pure dexlib2, read-only. Complements taffy_string_xref / taffy_dex_xref.",
            "search", ToolClass.EXTRA, heavy = false,
        ) {
            objectSchema(props {
                "path" str "APK 或 .dex 文件路径"
                "method" str "入口方法: 类名(可含 ->方法名) 如 Lcom/a/B;->foo 或 Lcom/a/B;"
                "direction" str "callers(谁调用它) / callees(它调用谁) / both, 默认 callees"
                "depth" str "展开层数(默认 3, 最大 6)"
                "limit" str "最多返回多少条边(默认 200)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val path = args.str("path")
            val entryRaw = args.str("method")
            if (path.isBlank()) return err("INVALID_ARGUMENT", "缺少 path", "path", "")
            if (entryRaw.isBlank()) return err("INVALID_ARGUMENT", "缺少 method", "method", "")
            val direction = args.str("direction").ifBlank { "callees" }.lowercase()
            if (direction !in listOf("callers", "callees", "both"))
                return err("INVALID_ARGUMENT", "direction 只能是 callers/callees/both", "direction", direction)
            val depth = (args.str("depth").toIntOrNull() ?: 3).coerceIn(1, 6)
            val limit = (args.str("limit").toIntOrNull() ?: 200).coerceIn(1, 2000)
            val f = File(path)
            if (!f.isFile) return err("FILE_NOT_FOUND", "文件不存在: $path", "path", path)

            return runCatching {
                val dexFiles = if (path.lowercase().endsWith(".dex"))
                    listOf(f.name to DexFileFactory.loadDexFile(f, Opcodes.getDefault()))
                else loadAllDexFromApk(f)

                // 索引: 方法签名 -> Method（用于判断是否有实现）；callee -> callers（反向）
                val bySig = HashMap<String, Method>()
                val implSet = HashSet<String>()
                for ((_, dex) in dexFiles) for (cls in dex.classes) for (m in cls.methods) {
                    val sig = sig(m)
                    bySig[sig] = m
                    if (m.implementation != null) implSet.add(sig)
                }

                val needCallers = direction == "callers" || direction == "both"
                val needCallees = direction == "callees" || direction == "both"
                val callersIdx: HashMap<String, MutableList<String>>? =
                    if (needCallers) HashMap() else null
                if (callersIdx != null) {
                    for ((_, dex) in dexFiles) for (cls in dex.classes) for (m in cls.methods) {
                        val impl = m.implementation ?: continue
                        val callerSig = sig(m)
                        for (insn in impl.instructions) {
                            if (insn is ReferenceInstruction) {
                                val r = insn.reference
                                if (r is MethodReference) {
                                    val calleeSig = sig(r)
                                    callersIdx.getOrPut(calleeSig) { mutableListOf() }.add(callerSig)
                                }
                            }
                        }
                    }
                }

                // 入口匹配: 精确签名 / 类->方法前缀 / 类名
                val roots = resolveRoots(entryRaw, bySig.keys)
                if (roots.isEmpty())
                    return err("METHOD_NOT_FOUND",
                        "未找到匹配方法: $entryRaw（可只给类名，或 类->方法名）", "method", entryRaw)

                val calleeFn: (String) -> List<String> = { s ->
                    val m = bySig[s]
                    if (m == null || m.implementation == null) emptyList()
                    else {
                        val out = LinkedHashSet<String>()
                        for (insn in m.implementation!!.instructions) {
                            if (insn is ReferenceInstruction) {
                                val r = insn.reference
                                if (r is MethodReference) out.add(sig(r))
                            }
                        }
                        out.toList()
                    }
                }

                val edges = JSONArray()
                val visited = HashSet<String>()
                var truncated = false
                val queue = ArrayDeque<Triple<String, Int, String>>() // (sig, depthFromRoot, via)
                roots.forEach { queue.add(Triple(it, 0, "")); visited.add(it) }

                bfs@ while (queue.isNotEmpty()) {
                    val (cur, d, via) = queue.removeFirst()
                    if (d >= depth) continue
                    if (d > 0) {
                        if (edges.length() >= limit) { truncated = true; break@bfs }
                        edges.put(JSONObject()
                            .put("depth", d)
                            .put("from", via)
                            .put("to", cur)
                            .put("hasImpl", implSet.contains(cur)))
                    }
                    val next: List<String> = when {
                        direction == "callers" -> callersIdx?.get(cur).orEmpty()
                        direction == "callees" -> calleeFn(cur)
                        else -> (callersIdx?.get(cur).orEmpty() + calleeFn(cur))
                    }
                    for (n in next) {
                        if (visited.add(n)) queue.add(Triple(n, d + 1, cur))
                    }
                }

                ok(JSONObject()
                    .put("tool", "taffy_call_graph")
                    .put("path", path)
                    .put("entry", entryRaw)
                    .put("matchedRoots", JSONArray(roots))
                    .put("dexCount", dexFiles.size)
                    .put("totalMethods", bySig.size)
                    .put("direction", direction)
                    .put("depth", depth)
                    .put("edgeCount", edges.length())
                    .put("truncated", truncated)
                    .put("edges", edges)
                    .put("hint", if (edges.length() == 0)
                        "该方向在指定层数内没有边（方法可能已被内联/混淆，或入口只在 so 侧实现）。可加大 depth 或换 direction。"
                    else "from=当前层父节点, to=本次命中的方法；hasImpl=false 表示目标方法无 DEX 实现（多半在 native/外部库）。"))
            }.getOrElse { e ->
                err("CALL_GRAPH_FAILED", "调用图构建失败: ${e.message ?: e.javaClass.simpleName}", "path", path)
            }
        }
    }

    private fun sig(m: Method): String = sig(m.definingClass, m.name, m.parameterTypes.joinToString("") { it.toString() }, m.returnType)
    private fun sig(r: MethodReference): String = sig(r.definingClass, r.name, r.parameterTypes.joinToString("") { it.toString() }, r.returnType)
    private fun sig(cls: String, name: String, params: String, ret: String) = "$cls->$name($params)$ret"

    private fun resolveRoots(raw: String, all: Set<String>): List<String> {
        val q = raw.trim()
        all.filter { it == q }.takeIf { it.isNotEmpty() }?.let { return it }
        val byPrefix = all.filter { it.startsWith(q) }
        if (byPrefix.isNotEmpty()) {
            // 若给的是「类->方法」但缺参数/返回, 取该类下所有同名方法的精简签名
            return if (q.contains("->")) byPrefix.sortedBy { it.length }.take(8) else byPrefix.take(8)
        }
        // 容错: 用户给 Lcom/a/B;->foo 但实际是 Lcom/a/B;->foo()V 之类
        return all.filter { it.contains(q) }.sortedBy { it.length }.take(8)
    }

    private fun loadAllDexFromApk(apk: File): List<Pair<String, DexFile>> {
        val out = mutableListOf<Pair<String, DexFile>>()
        ZipFile(apk).use { zf ->
            zf.entries().toList()
                .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                .sortedBy { it.name }
                .forEach { e ->
                    runCatching {
                        val tmp = File.createTempFile("taffy-cg-", ".dex")
                        zf.getInputStream(e).use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
                        val dex = DexFileFactory.loadDexFile(tmp, Opcodes.getDefault())
                        tmp.delete()
                        out.add(e.name to dex)
                    }
                }
        }
        return out
    }

    val ALL = listOf(tool)
}
