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

/**
 * 子代理消息历史的落盘编解码。
 *
 * 只保留重放所需的最小字段：role / parts(kind+text|tool 四元组)。不存 usage、不存 reasoning
 * 之外的东西——reasoning 要存，因为部分供应商在后续轮次里依赖它保持推理连贯。
 *
 * 解码对未知 kind 与畸形条目一律跳过，绝不因为一条坏数据让整次恢复失败。
 */
internal object AgentHistoryCodec {

    fun encode(messages: List<com.soreverse.mcp.core.RikkaMessage>): JSONArray {
        val out = JSONArray()
        messages.forEach { m ->
            val parts = JSONArray()
            m.parts.forEach { part ->
                when (part) {
                    is com.soreverse.mcp.core.RikkaPart.Text ->
                        parts.put(JSONObject().put("kind", "text").put("text", part.text))
                    is com.soreverse.mcp.core.RikkaPart.Reasoning ->
                        parts.put(JSONObject().put("kind", "reasoning").put("text", part.text))
                    is com.soreverse.mcp.core.RikkaPart.Tool ->
                        parts.put(
                            JSONObject()
                                .put("kind", "tool")
                                .put("id", part.id)
                                .put("name", part.name)
                                .put("arguments", part.arguments)
                                .put("result", part.result ?: JSONObject.NULL)
                                .put("index", part.index)
                        )
                }
            }
            out.put(JSONObject().put("role", m.role).put("parts", parts))
        }
        return out
    }

    fun decode(arr: JSONArray): List<com.soreverse.mcp.core.RikkaMessage> {
        val out = ArrayList<com.soreverse.mcp.core.RikkaMessage>(arr.length())
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val role = m.optString("role")
            if (role.isBlank()) continue
            val partsArr = m.optJSONArray("parts") ?: JSONArray()
            val parts = ArrayList<com.soreverse.mcp.core.RikkaPart>(partsArr.length())
            for (j in 0 until partsArr.length()) {
                val p = partsArr.optJSONObject(j) ?: continue
                when (p.optString("kind")) {
                    "text" -> parts.add(com.soreverse.mcp.core.RikkaPart.Text(p.optString("text")))
                    "reasoning" -> parts.add(com.soreverse.mcp.core.RikkaPart.Reasoning(p.optString("text")))
                    "tool" -> parts.add(
                        com.soreverse.mcp.core.RikkaPart.Tool(
                            id = p.optString("id"),
                            name = p.optString("name"),
                            arguments = p.optString("arguments"),
                            result = if (p.isNull("result")) null else p.optString("result"),
                            index = p.optInt("index"),
                        )
                    )
                }
            }
            out.add(com.soreverse.mcp.core.RikkaMessage(role, parts))
        }
        return out
    }
}

/** run.json 的反向解析：恢复运行只需要「计划」和「已完成的节点结果」。 */
internal object AgentRecordCodec {

    fun decodePlan(o: JSONObject): List<AgentNode> {
        val arr = o.optJSONArray("plan") ?: return emptyList()
        val out = ArrayList<AgentNode>(arr.length())
        for (i in 0 until arr.length()) {
            val n = arr.optJSONObject(i) ?: continue
            val id = n.optString("id")
            if (id.isBlank()) continue
            val deps = ArrayList<String>()
            val d = n.optJSONArray("dependsOn")
            if (d != null) {
                for (j in 0 until d.length()) {
                    val s = d.optString(j)
                    if (s.isNotBlank()) deps.add(s)
                }
            }
            out.add(
                AgentNode(
                    id = id,
                    roleId = n.optString("role"),
                    task = n.optString("task"),
                    dependsOn = deps,
                    aggregate = n.optBoolean("aggregate"),
                )
            )
        }
        return out
    }

    fun decodeResults(o: JSONObject): List<NodeResult> {
        val arr = o.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<NodeResult>(arr.length())
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val id = r.optString("nodeId")
            if (id.isBlank()) continue
            val ev = ArrayList<AgentEvidence>()
            val evArr = r.optJSONArray("evidence")
            if (evArr != null) {
                for (j in 0 until evArr.length()) {
                    val e = evArr.optJSONObject(j) ?: continue
                    val claim = e.optString("claim")
                    if (claim.isBlank()) continue
                    ev.add(AgentEvidence(claim, e.optString("where"), e.optString("confidence", "low")))
                }
            }
            val tools = ArrayList<String>()
            val tArr = r.optJSONArray("toolsUsed")
            if (tArr != null) for (j in 0 until tArr.length()) tools.add(tArr.optString(j))
            val unresolved = ArrayList<String>()
            val uArr = r.optJSONArray("unresolvedTools")
            if (uArr != null) for (j in 0 until uArr.length()) unresolved.add(uArr.optString(j))

            out.add(
                NodeResult(
                    nodeId = id,
                    role = r.optString("role"),
                    task = r.optString("task"),
                    status = r.optString("status"),
                    text = r.optString("text"),
                    evidence = ev,
                    toolsUsed = tools,
                    unresolvedTools = unresolved,
                    ms = r.optLong("ms"),
                    error = r.optString("error"),
                    reportPath = r.optString("reportPath"),
                )
            )
        }
        return out
    }
}
