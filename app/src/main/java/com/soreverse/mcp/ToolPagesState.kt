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

    // ---- 模拟页 (Unidbg) 状态在 UnidbgPanel 内部；此处仅共享工作区 ----
}