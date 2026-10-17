package com.soreverse.mcp.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * A2UI（Agent-to-UI）声明式界面描述：解析 + 严格校验。
 *
 * 借鉴太墟 taixu 的 render_surface：模型不再返回大段文本，而是返回一份界面描述 JSON，
 * 由客户端渲染成原生 Compose 组件（省 token、排版可控、不会因为 Markdown 表格在手机上
 * 挤成一坨）。
 *
 * 结构：
 * {version:1, title:"可选标题", root:{type:"card", props:{...}, children:[...]}}
 *
 * 本文件是纯 JVM 实现（只依赖 org.json），不引用任何 android 或 Compose 类型，可直接单测。
 * 校验失败**不抛异常**，统一返回 [SurfaceParseResult.Failure]（带 message + path），
 * 让调用方（MCP 工具 / 对话渲染）自行决定回退策略。
 */

/** 组件白名单：渲染器必须对每个 type 都有分支，否则跳过。 */
object SurfaceTypes {
    const val CARD: String = "card"
    const val TEXT: String = "text"
    const val KEYVALUE: String = "keyvalue"
    const val TABLE: String = "table"
    const val CODE: String = "code"
    const val CHIPS: String = "chips"
    const val ALERT: String = "alert"
    const val PROGRESS: String = "progress"
    const val BUTTON: String = "button"
    const val LIST: String = "list"
    const val DIVIDER: String = "divider"

    /** 全部允许的 type（顺序即文档顺序）。 */
    val all: List<String> = listOf(CARD, TEXT, KEYVALUE, TABLE, CODE, CHIPS, ALERT, PROGRESS, BUTTON, LIST, DIVIDER)

    /** 白名单集合，便于 O(1) 判定。 */
    val set: Set<String> = all.toSet()
}

/**
 * 限额：防模型输出炸弹（递归 JSON、超长字符串、几万个节点）。
 * 超出任何一项即整体失败，不做截断 —— 半截界面比纯文本回退更糟。
 */
data class SurfaceLimits(
    val maxNodes: Int = 200,
    val maxDepth: Int = 8,
    val maxString: Int = 4000,
    val maxChildren: Int = 50,
)

/** 一个界面节点。props 保留原始 JSON（渲染器按 type 自行取字段）。 */
data class SurfaceNode(
    val type: String,
    val props: JSONObject,
    val children: List<SurfaceNode> = emptyList(),
) {
    /** 规范化输出：省略空 props / 空 children，便于工具回显。 */
    fun toJson(): JSONObject {
        val out = JSONObject().put("type", type)
        if (props.length() > 0) out.put("props", props)
        if (children.isNotEmpty()) {
            val arr = JSONArray()
            for (child in children) arr.put(child.toJson())
            out.put("children", arr)
        }
        return out
    }
}

/** 校验通过的界面描述。warnings 是被跳过节点的诊断（不影响整体可用）。 */
data class SurfaceSpec(
    val version: Int = 1,
    val title: String? = null,
    val root: SurfaceNode,
    val warnings: List<String> = emptyList(),
    val nodeCount: Int = 1,
) {
    fun toJson(): JSONObject {
        val out = JSONObject().put("version", version)
        val t = title
        if (t != null && t.isNotBlank()) out.put("title", t)
        out.put("root", root.toJson())
        return out
    }
}

/** 解析错误：message 给人看，path 给模型看（指明是哪个字段炸的，便于自我修正）。 */
data class SurfaceParseError(val message: String, val path: String)

/** 解析结果（sealed，避免调用方忘记处理失败分支）。 */
sealed class SurfaceParseResult {
    data class Success(val spec: SurfaceSpec) : SurfaceParseResult()
    data class Failure(val error: SurfaceParseError) : SurfaceParseResult()

    val specOrNull: SurfaceSpec? get() = (this as? Success)?.spec
    val errorOrNull: SurfaceParseError? get() = (this as? Failure)?.error
}

/**
 * 解析器：JSON 字符串 -> [SurfaceSpec]。
 *
 * 规则：
 * 1. version 缺省视为 1；显式给出且不为 1 时失败。
 * 2. type 不在白名单 -> 跳过该节点及其子树，累加 warning，不整体失败。
 * 3. 节点数 / 深度 / 单字符串长度 / 每层 children 数超限 -> 整体失败（带 path）。
 * 4. props 必须是对象；children 必须是数组。
 */
object SurfaceParser {

    const val VERSION: Int = 1

    fun parse(json: String, limits: SurfaceLimits = SurfaceLimits()): SurfaceParseResult {
        if (json.isBlank()) return SurfaceParseResult.Failure(SurfaceParseError("spec 为空", "$"))
        val obj = try {
            JSONObject(json)
        } catch (e: StackOverflowError) {
            return SurfaceParseResult.Failure(SurfaceParseError("spec 嵌套过深，无法解析", "$"))
        } catch (e: Exception) {
            val detail = (e.message ?: e.javaClass.simpleName).take(200)
            return SurfaceParseResult.Failure(SurfaceParseError("JSON 解析失败: $detail", "$"))
        }

        val version = if (obj.has("version")) obj.optInt("version", -1) else VERSION
        if (version != VERSION) {
            return SurfaceParseResult.Failure(
                SurfaceParseError("不支持的 version: $version（当前仅支持 $VERSION）", "$.version"),
            )
        }

        val titleRaw = obj.opt("title")
        val title = if (titleRaw == null || titleRaw == JSONObject.NULL) "" else titleRaw.toString()
        if (title.length > limits.maxString) {
            return SurfaceParseResult.Failure(
                SurfaceParseError("title 长度 ${title.length} 超过限额 ${limits.maxString}", "$.title"),
            )
        }

        val rootRaw = obj.opt("root")
        if (rootRaw !is JSONObject) {
            return SurfaceParseResult.Failure(
                SurfaceParseError("缺少 root 对象（root 必须是 {type, props, children}）", "$.root"),
            )
        }

        val walker = Walker(limits)
        val root = try {
            walker.build(rootRaw, "$.root", 1)
        } catch (abort: Abort) {
            return SurfaceParseResult.Failure(abort.error)
        }
        if (root == null) {
            val rootType = rootRaw.optString("type", "")
            return SurfaceParseResult.Failure(
                SurfaceParseError("root 组件类型不受支持: '$rootType'（允许: " + SurfaceTypes.all.joinToString("/") + "）", "$.root.type"),
            )
        }

        return SurfaceParseResult.Success(
            SurfaceSpec(
                version = version,
                title = title.ifBlank { null },
                root = root,
                warnings = walker.warnings.toList(),
                nodeCount = walker.count,
            ),
        )
    }

    /** 内部中断信号（对外一律转成 Failure，不让异常穿出去）。 */
    private class Abort(val error: SurfaceParseError) : Exception(error.message)

    private class Walker(private val limits: SurfaceLimits) {
        var count: Int = 0
        val warnings: MutableList<String> = mutableListOf()

        fun build(obj: JSONObject, path: String, depth: Int): SurfaceNode? {
            if (depth > limits.maxDepth) {
                throw Abort(SurfaceParseError("嵌套深度超过限额 ${limits.maxDepth} 层", path))
            }
            val type = obj.optString("type", "").trim()
            if (type.isEmpty()) {
                warnings.add("$path 缺少 type，已跳过")
                return null
            }
            if (type !in SurfaceTypes.set) {
                warnings.add("$path 的类型 '$type' 不在白名单内，已跳过（允许: " + SurfaceTypes.all.joinToString("/") + "）")
                return null
            }
            val props = if (obj.has("props")) {
                val raw = obj.opt("props")
                if (raw !is JSONObject) throw Abort(SurfaceParseError("props 必须是 JSON 对象", "$path.props"))
                JSONObject(raw.toString())
            } else {
                JSONObject()
            }
            for (key in props.keys()) {
                checkValue(props.opt(key), "$path.props.$key", 0)
            }

            count += 1
            if (count > limits.maxNodes) {
                throw Abort(SurfaceParseError("节点数超过限额 ${limits.maxNodes}", path))
            }

            val children = mutableListOf<SurfaceNode>()
            val kidsRaw = obj.opt("children")
            if (kidsRaw is JSONArray) {
                if (kidsRaw.length() > limits.maxChildren) {
                    throw Abort(
                        SurfaceParseError(
                            "children 数量 ${kidsRaw.length()} 超过限额 ${limits.maxChildren}",
                            "$path.children",
                        ),
                    )
                }
                for (i in 0 until kidsRaw.length()) {
                    val childRaw = kidsRaw.opt(i)
                    if (childRaw !is JSONObject) {
                        warnings.add("$path.children[$i] 不是对象，已跳过")
                        continue
                    }
                    val child = build(childRaw, "$path.children[$i]", depth + 1)
                    if (child != null) children.add(child)
                }
            } else if (kidsRaw != null && kidsRaw != JSONObject.NULL) {
                throw Abort(SurfaceParseError("children 必须是数组", "$path.children"))
            }
            return SurfaceNode(type = type, props = props, children = children)
        }

        /** 递归校验 props 里的字符串长度（数组/对象内部的字符串同样受限）。 */
        private fun checkValue(value: Any?, path: String, depth: Int) {
            if (depth > 16) throw Abort(SurfaceParseError("props 嵌套过深（>16 层）", path))
            when (value) {
                null -> return
                is String -> {
                    if (value.length > limits.maxString) {
                        throw Abort(SurfaceParseError("字符串长度 ${value.length} 超过限额 ${limits.maxString}", path))
                    }
                }
                is JSONArray -> {
                    if (value.length() > SURFACE_MAX_ARRAY_ITEMS) {
                        throw Abort(SurfaceParseError("数组元素 ${value.length()} 超过限额 $SURFACE_MAX_ARRAY_ITEMS", path))
                    }
                    for (i in 0 until value.length()) checkValue(value.opt(i), "$path[$i]", depth + 1)
                }
                is JSONObject -> {
                    for (k in value.keys()) checkValue(value.opt(k), "$path.$k", depth + 1)
                }
                else -> return
            }
        }
    }

    /** props 内部单个数组的元素上限（table rows / list items 之类）。 */
    const val SURFACE_MAX_ARRAY_ITEMS: Int = 200
}

/**
 * 从模型回复的 Markdown 文本里识别 ```surface 围栏代码块。
 * 纯字符串处理，同样可单测：识别 / 抽取 JSON / 剥掉围栏得到剩余文本。
 */
object SurfaceBlocks {

    const val FENCE: String = "`" + "`" + "`"
    const val MARKER: String = FENCE + "surface"

    /** 是否含 surface 代码块（开销极小，可每次重组调用）。 */
    fun contains(text: String): Boolean = text.contains(MARKER)

    /** 抽取所有 surface 块内的正文（未解析的原始 JSON 文本）。 */
    fun extract(text: String): List<String> = spans(text).map { it.body }

    /** 去掉所有 surface 块，保留其余文本（可能为空串）。 */
    fun strip(text: String): String {
        val parts = spans(text)
        if (parts.isEmpty()) return text
        val sb = StringBuilder()
        var cursor = 0
        for (span in parts) {
            if (span.start > cursor) sb.append(text, cursor, span.start)
            cursor = span.end
        }
        if (cursor < text.length) sb.append(text, cursor, text.length)
        return sb.toString()
    }

    private class Span(val start: Int, val end: Int, val body: String)

    private fun spans(text: String): List<Span> {
        val out = mutableListOf<Span>()
        var cursor = 0
        while (true) {
            val open = text.indexOf(MARKER, cursor)
            if (open < 0) break
            val bodyStart = text.indexOf('\n', open)
            if (bodyStart < 0) break
            var close = -1
            var search = bodyStart + 1
            while (true) {
                val idx = text.indexOf(FENCE, search)
                if (idx < 0) break
                val lineBegin = text.lastIndexOf('\n', idx - 1) + 1
                if (text.substring(lineBegin, idx).isBlank()) {
                    close = idx
                    break
                }
                search = idx + FENCE.length
            }
            if (close < 0) {
                out.add(Span(open, text.length, text.substring(bodyStart + 1).trim()))
                break
            }
            var bodyEnd = close
            while (bodyEnd > bodyStart && (text[bodyEnd - 1] == '\n' || text[bodyEnd - 1] == '\r')) bodyEnd -= 1
            out.add(Span(open, close + FENCE.length, text.substring(bodyStart + 1, bodyEnd)))
            cursor = close + FENCE.length
        }
        return out
    }
}
