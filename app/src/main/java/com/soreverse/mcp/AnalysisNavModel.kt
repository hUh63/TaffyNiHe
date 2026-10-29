package com.soreverse.mcp

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 分析页侧栏/「更多视图」的导航模型。
 *
 * 单独一个文件是为了让 AnalysisWorkspace.kt 不再增长（arch-policy 对存量文件是
 * 「行数增长即 ERROR」）：新增视图时改这里，不要往那个 13000 行的文件里塞。
 */
internal data class AnalysisNavItem(val key: String, val short: String, val en: String, val icon: ImageVector)

internal val analysisNavItems = listOf(
    AnalysisNavItem("home", "主页", "Home", Icons.Filled.Home),
    AnalysisNavItem("back", "返回", "Back", Icons.AutoMirrored.Filled.ArrowBack),
    AnalysisNavItem("functions", "函数", "Fns", Icons.Filled.Memory),
    AnalysisNavItem("search", "搜索", "Find", Icons.Filled.Search),
    AnalysisNavItem("disasm", "汇编", "Asm", Icons.Filled.Code),
    AnalysisNavItem("pseudo", "伪C", "Pseudo", Icons.Filled.Description),
    AnalysisNavItem("cfg", "CFG", "CFG", Icons.Filled.CompareArrows),
    AnalysisNavItem("strings", "字符串", "Str", Icons.Filled.DataObject),
    AnalysisNavItem("symbols", "符号", "Sym", Icons.Filled.ListAlt),
    AnalysisNavItem("imports", "导入", "Imp", Icons.Filled.Link),
    AnalysisNavItem("sections", "节区", "Sec", Icons.Filled.Inventory2),
    AnalysisNavItem("elfhdr", "ELF头", "ELF", Icons.Filled.Info),
    AnalysisNavItem("segments", "程序段", "Seg", Icons.Filled.Inventory2),
    AnalysisNavItem("relocs", "重定位", "Rel", Icons.Filled.Link),
    AnalysisNavItem("dynamic", "动态", "Dyn", Icons.Filled.FlashOn),
    AnalysisNavItem("libraries", "依赖库", "Lib", Icons.Filled.FolderOpen),
    AnalysisNavItem("hashes", "哈希", "Hash", Icons.Filled.LockOpen),
    AnalysisNavItem("versions", "版本", "Ver", Icons.Filled.Refresh),
    AnalysisNavItem("entries", "入口点", "Ent", Icons.Filled.MyLocation),
    AnalysisNavItem("hex", "HEX", "Hex", Icons.Filled.Storage),
    AnalysisNavItem("demangle", "C++", "C++", Icons.Filled.Transform),
    AnalysisNavItem("base", "进制", "Base", Icons.Filled.Calculate),
    AnalysisNavItem("asm", "汇编器", "Asm+", Icons.Filled.SwapHoriz),
    AnalysisNavItem("results", "结果", "Out", Icons.Filled.Terminal),
    AnalysisNavItem("tools", "工具", "Tools", Icons.Filled.Build),
    AnalysisNavItem("xor", "XOR", "XOR", Icons.Filled.LockOpen),
    AnalysisNavItem("regs", "寄存器", "Reg", Icons.Filled.Memory),
    AnalysisNavItem("strdec", "解码", "Dec", Icons.Filled.DataObject),
    AnalysisNavItem("bytediff", "差分", "Diff", Icons.Filled.CompareArrows),
    AnalysisNavItem("sodiff", "SO对比", "SODiff", Icons.Filled.Inventory2),
    AnalysisNavItem("insnexp", "指令", "Insn", Icons.Filled.Description),
    AnalysisNavItem("asm2c", "译C", "→C", Icons.Filled.Transform),
    AnalysisNavItem("asm2flow", "框图", "Flow", Icons.Filled.Inventory2),
    AnalysisNavItem("vtable", "虚表", "Vtab", Icons.Filled.Transform),
    AnalysisNavItem("xrefs", "引用", "XRef", Icons.Filled.Link),
    AnalysisNavItem("addrview", "地址", "Addr", Icons.Filled.MyLocation),
    AnalysisNavItem("funcsig", "签名", "Sig", Icons.Filled.ListAlt),
    AnalysisNavItem("jnireg", "JNI", "JNI", Icons.Filled.DataObject),
    AnalysisNavItem("hardening", "加固", "Hard", Icons.Filled.Warning),
    AnalysisNavItem("ollvm", "去平坦化", "DeFlat", Icons.Filled.Transform),
    AnalysisNavItem("strcipher", "密文恢复", "Cipher", Icons.Filled.LockOpen),
    AnalysisNavItem("obfscan", "混淆扫描", "Obf", Icons.Filled.Transform),
    AnalysisNavItem("agentrun", "多智能体", "Agents", Icons.Filled.Storage),
    AnalysisNavItem("callgraph", "调用图", "Calls", Icons.Filled.CompareArrows),
    AnalysisNavItem("export", "导出", "Export", Icons.Filled.Terminal),
    AnalysisNavItem("unpack", "脱壳", "Unpk", Icons.Filled.LockOpen),
    AnalysisNavItem("data", "数据", "Data", Icons.Filled.Inventory2),
    AnalysisNavItem("funcdetail", "函数详情", "Func", Icons.Filled.Code),
    AnalysisNavItem("funcinfo", "函数信息", "Info", Icons.Filled.Description),
    AnalysisNavItem("comments", "注释", "Note", Icons.Filled.Description),
    AnalysisNavItem("analyze", "分析", "Ana", Icons.Filled.FlashOn),
    AnalysisNavItem("flutter", "Flutter", "Flutter", Icons.Filled.Memory),
    AnalysisNavItem("edit", "编辑", "Edit", Icons.Filled.Build),
    AnalysisNavItem("globalc", "全局伪C", "GpC", Icons.Filled.Description),
    AnalysisNavItem("calc", "计算器", "Calc", Icons.Filled.Calculate),
    AnalysisNavItem("iapp", "iApp", "iApp", Icons.Filled.LockOpen),
)
