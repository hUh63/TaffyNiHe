package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.ToolPerf
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONObject

/**
 * 工具调用性能账本（只读观测 + 重置）。
 *
 * 排查「MCP 很慢」时先看这里：totalMs 高的工具就是真正的瓶颈；
 * 如果所有工具的 avgMs 都很小但客户端觉得慢，问题在排队（HeavyGate / 线程池）或网络，
 * 不在工具本身。maxMs 远高于 avgMs 说明有偶发的长尾（例如某个大文件）。
 */
internal object PerfTool {

    val tool: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_perf",
            "【性能账本】按工具统计调用次数/总耗时/均值/峰值/错误数，用于定位慢工具；action=snapshot(默认)|slowest|reset。" +
                "排查「MCP 变慢」请先看这里：totalMs 高的是真瓶颈，avgMs 都小而体感慢则是排队或网络。",
            "Per-tool call statistics (calls/total/avg/max ms, errors). action=snapshot (default) | slowest | reset. " +
                "If every avgMs is small but calls feel slow, the bottleneck is queueing or transport, not the tool.",
            "workspace",
            ToolClass.EXTRA,
            heavy = false,
        ) {
            objectSchema(props {
                "action" str "snapshot (default) | slowest (top 10 by total) | reset"
                "top" int "snapshot: 返回前 N 行（0 = 全部，默认 0）"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val action = args.str("action", "snapshot")
            return when (action) {
                "reset" -> {
                    ToolPerf.reset()
                    ok(JSONObject().put("action", "reset").put("distinctTools", 0))
                }
                "slowest" -> ok(
                    JSONObject()
                        .put("action", "slowest")
                        .put("tools", ToolPerf.snapshot(10))
                        .put("distinctTools", ToolPerf.distinctTools()),
                )
                "snapshot" -> ok(
                    JSONObject()
                        .put("action", "snapshot")
                        .put("tools", ToolPerf.snapshot(args.intValue("top", 0)))
                        .put("distinctTools", ToolPerf.distinctTools())
                        .put("note", "只统计工具真正执行的时间，不含排队与传输"),
                )
                else -> ok(
                    JSONObject()
                        .put("action", action)
                        .put("tools", ToolPerf.snapshot(0))
                        .put("distinctTools", ToolPerf.distinctTools())
                        .put("warning", "未知 action，已返回 snapshot"),
                )
            }
        }
    }

    val ALL = listOf(tool)
}
