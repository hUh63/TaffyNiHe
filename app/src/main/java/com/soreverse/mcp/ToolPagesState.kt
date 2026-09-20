// 六个工具页的独立 UI 状态 + 跨页共享工作区
package com.soreverse.mcp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal val DEFAULT_FRIDA_SCRIPT = """
// Frida 动态插桩模板 —— 按需修改后经 MCP frida 服务下发
Java.perform(function () {
    var System = Java.use('java.lang.System');
    System.out.println('[frida] attached');
});
""".trimIndent()

/** 单个结果标签条目 */
data class ResultTab(val id: String, val label: String, val text: String)

/** 六个工具页独立状态 + 共享工作区。由 SoReverseApp remember 持有，切换页面不丢失。 */
class ToolPagesState {
    // ---- 共享工作区（所有工具页共用同一个引擎 workspace）----
    var sharedWorkspaceId by mutableStateOf("")
    var sharedSoName by mutableStateOf("")
    var opening by mutableStateOf(false)
    var openError by mutableStateOf("")

    // ---- 反编译页 ----
    var decompileTarget by mutableStateOf("")
    var decompileResult by mutableStateOf("")
    var decompileError by mutableStateOf("")
    var decompileRunning by mutableStateOf(false)
    /** 反编译页的额外功能结果（函数列表/反汇编等） */
    var decompileExtra by mutableStateOf("")
    /** 反汇编输入地址 */
    var disasmAddr by mutableStateOf("")

    // ---- 模拟页 ----
    var emulateSymbol by mutableStateOf("")
    var emulateResult by mutableStateOf("")
    var emulateError by mutableStateOf("")
    var emulateRunning by mutableStateOf(false)
    /** 模拟页的额外功能结果（寄存器/dump等） */
    var emulateExtra by mutableStateOf("")

    // ---- SO 分析页 ----
    var soOverview by mutableStateOf("")
    var soCrypto by mutableStateOf("")
    var soAnalyzeRunning by mutableStateOf(false)
    /** SO 分析页的额外功能结果（段信息/导入导出表等） */
    var soExtra by mutableStateOf("")

    // ---- 分析页 CFG 图形视图 ----
    /** 最近一次 CFG 查询的原始 JSON（rzCfg 输出）；空串表示尚未查询。 */
    var cfgJson by mutableStateOf("")
    /** CFG 图形视图是否激活（内容区切到画布）。 */
    var cfgVisible by mutableStateOf(false)
    /** 最近一次 CFG 查询目标（函数名/地址），用于画布标题。 */
    var cfgTarget by mutableStateOf("")

    // ---- 回编页 ----
    var rebuildCheck by mutableStateOf("")
    var rebuildResult by mutableStateOf("")
    var rebuildOutputs by mutableStateOf("")
    var rebuildError by mutableStateOf("")
    var rebuildRunning by mutableStateOf(false)
    /** 回编页的额外功能结果（hex补丁/重命名等） */
    var rebuildExtra by mutableStateOf("")

    // ---- 脱壳页 ----
    var unpackInfo by mutableStateOf("")
    var unpackRunning by mutableStateOf(false)
    /** 脱壳页的额外功能结果（提取SO等） */
    var unpackExtra by mutableStateOf("")

    // ---- Frida 页 ----
    var fridaScript by mutableStateOf(DEFAULT_FRIDA_SCRIPT)
    var fridaStatus by mutableStateOf("")

    // ---- 编辑页 ----
    var editorHexResult by mutableStateOf("")
    var editorTextResult by mutableStateOf("")
    var editorEditResult by mutableStateOf("")
    var editorDiffResult by mutableStateOf("")

    // ---- 标签页管理（每个工具独立） ----
    /** 当前工具的结果标签列表 */
    var resultTabs by mutableStateOf<List<ResultTab>>(emptyList())
    /** 当前选中的标签索引 */
    var selectedTabIndex by mutableStateOf(0)

    /** 向当前工具结果标签列表追加一个标签 */
    fun addTab(toolLabel: String, subLabel: String, text: String) {
        val id = "${toolLabel}_${subLabel}_${resultTabs.size}"
        val label = "$toolLabel·$subLabel"
        // 替换同名标签（已有相同 subLabel 则更新）
        val existing = resultTabs.indexOfFirst { it.label == label }
        if (existing >= 0) {
            resultTabs = resultTabs.toMutableList().also { it[existing] = it[existing].copy(text = text) }
            selectedTabIndex = existing
        } else {
            resultTabs = resultTabs + ResultTab(id, label, text)
            selectedTabIndex = resultTabs.lastIndex
        }
    }

    /** 关闭指定索引的标签 */
    fun closeTab(index: Int) {
        if (index < 0 || index >= resultTabs.size) return
        val list = resultTabs.toMutableList()
        list.removeAt(index)
        resultTabs = list
        if (selectedTabIndex >= resultTabs.size) selectedTabIndex = (resultTabs.size - 1).coerceAtLeast(0)
    }

    /** 清除所有标签 */
    fun clearTabs() {
        resultTabs = emptyList()
        selectedTabIndex = 0
    }

    // ---- 分析页：信息架构（导航视图 / 全局选中函数 / 视图缓存） ----
    /** 当前导航视图 key：functions / search / disasm / pseudo / cfg / strings / symbols / imports / sections / hex / results / tools */
    var analysisView by mutableStateOf("functions")
    /** 全页唯一选中的函数名（空 = 未选择）。反汇编 / 伪C / CFG 均以它为目标。 */
    var selectedFunctionName by mutableStateOf("")
    /** 选中函数的入口地址（hex 文本，可为空；为空时回退用函数名做 locator）。 */
    var selectedFunctionVa by mutableStateOf("")
    /** 函数列表的过滤关键字。 */
    var functionQuery by mutableStateOf("")
    /** 全局搜索关键字与检索范围。 */
    var searchQuery by mutableStateOf("")
    var searchScope by mutableStateOf("functions")
    /** 刷新令牌：自增即让分析页各视图重新取数（配合 clearViewCaches）。 */
    var reloadTick by mutableStateOf(0)

    /** 列表视图缓存：key = "<view>|<ws>|<prefix>" -> 引擎原始 JSON 文本。 */
    var viewCache by mutableStateOf<Map<String, String>>(emptyMap())
    /** 当前正在加载的缓存 key（空 = 空闲）；用于显示局部加载态。 */
    var viewLoading by mutableStateOf("")

    /** 反汇编结果（disasm 原始 JSON）与其对应的缓存 key。 */
    var disasmJson by mutableStateOf("")
    var disasmKey by mutableStateOf("")
    /** 伪 C 结果（rzDecompile 原始 JSON）与其对应的缓存 key。 */
    var pseudoJson by mutableStateOf("")
    var pseudoKey by mutableStateOf("")
    /** CFG 是否正在查询。 */
    var cfgLoading by mutableStateOf(false)

    /** 写入一个列表视图缓存。 */
    fun cacheView(key: String, json: String) {
        viewCache = viewCache + (key to json)
    }

    /** 清空列表 / 反汇编 / 伪C / CFG 缓存，让下一次取数重新落到引擎。 */
    fun clearViewCaches() {
        viewCache = emptyMap()
        viewLoading = ""
        disasmJson = ""
        disasmKey = ""
        pseudoJson = ""
        pseudoKey = ""
        cfgJson = ""
        cfgTarget = ""
    }

    // ---- 模拟页 (Unidbg) 状态在 UnidbgPanel 内部；此处仅共享工作区 ----
}