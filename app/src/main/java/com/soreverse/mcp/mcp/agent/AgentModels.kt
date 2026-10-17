package com.soreverse.mcp.mcp.agent

import org.json.JSONArray
import org.json.JSONObject

/** 一条可复核的证据：结论 + 定位 + 置信度。 */
internal data class AgentEvidence(
    val claim: String,
    val where: String,
    val confidence: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("claim", claim)
        .put("where", where)
        .put("confidence", confidence)
}

/** 计划里的一个节点。dependsOn 为空表示可与其他无依赖节点并行。 */
internal data class AgentNode(
    val id: String,
    val roleId: String,
    val task: String,
    val dependsOn: List<String> = emptyList(),
    /** 汇总节点：等所有前置完成后单独跑，输入是全部前置结论。 */
    val aggregate: Boolean = false,
)

/** 一个节点跑完的结果。 */
internal data class NodeResult(
    val nodeId: String,
    val role: String,
    val task: String,
    val status: String,
    val text: String,
    val evidence: List<AgentEvidence> = emptyList(),
    val toolsUsed: List<String> = emptyList(),
    val unresolvedTools: List<String> = emptyList(),
    val ms: Long = 0L,
    val error: String = "",
    val reportPath: String = "",
) {
    fun toJson(): JSONObject {
        val ev = JSONArray()
        evidence.forEach { ev.put(it.toJson()) }
        val tu = JSONArray()
        toolsUsed.forEach { tu.put(it) }
        val un = JSONArray()
        unresolvedTools.forEach { un.put(it) }
        return JSONObject()
            .put("nodeId", nodeId)
            .put("role", role)
            .put("task", task)
            .put("status", status)
            .put("text", text)
            .put("evidence", ev)
            .put("toolsUsed", tu)
            .put("unresolvedTools", un)
            .put("ms", ms)
            .put("error", error)
            .put("reportPath", reportPath)
    }
}

/** 一次编排运行的完整记录（落 run.json，可回看）。 */
internal data class AgentRunRecord(
    val runId: String,
    val goal: String,
    val workspaceId: String,
    val startedAt: Long,
    val finishedAt: Long,
    val status: String,
    val conclusion: String,
    val plan: List<AgentNode>,
    val results: List<NodeResult>,
    val budget: JSONObject,
) {
    fun toJson(): JSONObject {
        val pl = JSONArray()
        plan.forEach { n ->
            pl.put(
                JSONObject()
                    .put("id", n.id)
                    .put("role", n.roleId)
                    .put("task", n.task)
                    .put("dependsOn", JSONArray(n.dependsOn))
                    .put("aggregate", n.aggregate)
            )
        }
        val rs = JSONArray()
        results.forEach { rs.put(it.toJson()) }
        return JSONObject()
            .put("runId", runId)
            .put("goal", goal)
            .put("workspaceId", workspaceId)
            .put("startedAt", startedAt)
            .put("finishedAt", finishedAt)
            .put("status", status)
            .put("conclusion", conclusion)
            .put("plan", pl)
            .put("results", rs)
            .put("budget", budget)
    }
}

/** 证据解析：模型不一定会严格照格式来，所以解析失败就返回空而不是抛。 */
internal object AgentEvidenceParser {

    fun parse(text: String): List<AgentEvidence> {
        val marker = text.lastIndexOf("EVIDENCE:", ignoreCase = true)
        if (marker < 0) return emptyList()
        val tail = text.substring(marker + "EVIDENCE:".length)
        val start = tail.indexOf('[')
        if (start < 0) return emptyList()
        val end = tail.lastIndexOf(']')
        if (end <= start) return emptyList()
        val arr = runCatching { JSONArray(tail.substring(start, end + 1)) }.getOrNull() ?: return emptyList()
        val out = ArrayList<AgentEvidence>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val claim = o.optString("claim").ifBlank { o.optString("finding") }.trim()
            if (claim.isBlank()) continue
            out.add(
                AgentEvidence(
                    claim = claim,
                    where = o.optString("where").ifBlank { o.optString("location") }.trim(),
                    confidence = o.optString("confidence", "low").ifBlank { "low" }.lowercase(),
                )
            )
        }
        return out
    }

    /** 汇报正文：去掉 EVIDENCE 行，避免在 UI 里重复展示。 */
    fun body(text: String): String {
        val marker = text.lastIndexOf("EVIDENCE:", ignoreCase = true)
        return if (marker < 0) text.trim() else text.substring(0, marker).trim()
    }
}
