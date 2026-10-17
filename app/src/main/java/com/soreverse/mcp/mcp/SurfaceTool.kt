package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.SurfaceParseResult
import com.soreverse.mcp.core.SurfaceParser
import com.soreverse.mcp.core.SurfaceTypes
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject

/**
 * 塔菲逆核：A2UI 声明式界面网关（借鉴太墟 taixu 的 render_surface）。
 *
 * 解决的问题：模型把结构化结果（SO 扫描清单、函数表、加固检测结论）写成大段 Markdown 交回来，
 * 既费 token 又在手机上排不好版。改成让模型返回一份界面描述 JSON，App 侧渲染成原生 Compose 组件。
 *
 * 本工具只做**解析 + 严格校验**，把规范化后的 spec 与 warnings 回给模型确认
 * （模型因此能自我修正：路径 path 直接告诉它是哪个字段树炸的）；
 * 真正的渲染发生在对话气泡里（见 DeepChatComponents.DeepAssistantBody + ui/A2UiRenderer）。
 *
 * 入参：spec（JSON 字符串）。也接受别名 a2ui / surface / json，降低模型拼错字段名的失败率。
 */
object SurfaceTool {

    private const val NAME: String = "taffy_render_surface"

    private val typeList: String = SurfaceTypes.all.joinToString("/")

    val render: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            NAME,
            "【声明式界面 A2UI】不要用大段文本汇报结构化结果，改为返回一份界面描述给 App 渲染原生组件。" +
                "入参 spec 是 JSON **字符串**，结构为 {\"version\":1,\"title\":\"可选标题\",\"root\":{...}}；" +
                "节点为 {\"type\":..., \"props\":{...}, \"children\":[...]}。" +
                "可用 type：" + typeList + "。" +
                "props 约定：text→{value,tone?(muted|ok|warn)}；keyvalue→{rows:[[\"键\",\"值\"]]}；" +
                "table→{columns:[\"列\"],rows:[[\"单元格\"]]}；code→{value,lang}；chips→{items:[\"标签\"]}；" +
                "alert→{tone:warn,text}；progress→{value:0..1,label}；button→{label,action,payload}；" +
                "list→{items:[{title,subtitle,meta}]}；card→{title}+children；divider→无 props。" +
                "限额：节点总数≤200、嵌套≤8 层、单字符串≤4000 字符、每层 children≤50（超限直接报错）。" +
                "未知 type 会被跳过并记入 warnings（不整体失败）。" +
                "渲染时机：在对话里用 ```surface 代码块包住同一份 JSON 就会渲染成界面。",
            "Declarative A2UI surface gateway: return a UI description instead of long Markdown so the app renders " +
                "native Compose widgets. spec is a JSON string: {\"version\":1,\"title\":optional,\"root\":{...}}; " +
                "node = {\"type\":..., \"props\":{...}, \"children\":[...]}. Allowed types: " + typeList + ". " +
                "Props: text{value,tone: muted|ok|warn}, keyvalue{rows:[[k,v]]}, table{columns,rows}, code{value,lang}, " +
                "chips{items}, alert{tone,text}, progress{value 0..1,label}, button{label,action,payload}, " +
                "list{items:[{title,subtitle,meta}]}, card{title}+children, divider. " +
                "Limits: <=200 nodes, <=8 levels deep, <=4000 chars per string, <=50 children per level. " +
                "Unknown types are skipped and reported in warnings. Wrap the same JSON in a ```surface block " +
                "inside a normal reply to have it rendered inline.",
            "ui",
            ToolClass.EXTRA,
            heavy = false,
        ) {
            objectSchema(props {
                "spec" str "A2UI 界面描述 JSON 字符串：{version:1,title?,root:{type,props,children}}（见工具描述里的 type/props 约定与限额）"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val raw = args.str("spec")
                .ifBlank { args.str("a2ui") }
                .ifBlank { args.str("surface") }
                .ifBlank { args.str("json") }
            if (raw.isBlank()) {
                return err("INVALID_ARGUMENT", "缺少 spec（JSON 字符串形式的 A2UI 界面描述）", "spec", "")
            }
            return when (val result = SurfaceParser.parse(raw)) {
                is SurfaceParseResult.Success -> {
                    val spec = result.spec
                    val warnings = JSONArray()
                    spec.warnings.forEach { warnings.put(it) }
                    ok(
                        JSONObject()
                            .put("tool", NAME)
                            .put("spec", spec.toJson())
                            .put("warnings", warnings)
                            .put("nodes", spec.nodeCount)
                            .put(
                                "hint",
                                "在回复里用 ```surface 代码块包住同一份 JSON 即可渲染成界面",
                            ),
                    )
                }
                is SurfaceParseResult.Failure -> err(
                    "SURFACE_PARSE_ERROR",
                    result.error.message,
                    "spec",
                    "",
                    "path" to result.error.path,
                    "allowedTypes" to SurfaceTypes.all.joinToString("/"),
                )
            }
        }
    }

    val ALL: List<ToolHandler> = listOf(render)
}
