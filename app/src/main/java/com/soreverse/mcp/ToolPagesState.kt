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

    // ---- 模拟页 (Unidbg) 状态在 UnidbgPanel 内部；此处仅共享工作区 ----
}