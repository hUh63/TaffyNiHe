package com.soreverse.mcp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Rizin 引擎信息页：内置 rz 引擎的工具路由、反编译能力说明与常用命令速查。
 */
@Composable
internal fun RizinPage(t: UiText) {
    val zh = t.zh
    val rootTools = listOf(
        "taffy_so_open" to (if (zh) "打开 SO/ELF（Rizin 分析会话）" else "Open SO/ELF (Rizin session)"),
        "taffy_analyze_functions" to (if (zh) "函数列表与符号" else "Functions & symbols"),
        "taffy_analyze_cfg" to (if (zh) "控制流图" else "Control flow graph"),
        "taffy_analyze_xrefs" to (if (zh) "交叉引用" else "Cross references"),
        "taffy_read_disasm" to (if (zh) "反汇编" else "Disassembly"),
        "taffy_rz" to (if (zh) "Rizin 原生命令 / ESIL 模拟 / 反编译(rizin-ghidra)" else "Native rz / ESIL / decompile"),
        "taffy_build_so" to (if (zh) "回写并签名构建 SO" else "Rebuild & sign SO"),
    )
    val quickCommands = listOf(
        "af   函数分析", "afl  函数列表", "pdf @addr  函数反汇编",
        "pdc @addr  反编译（需 ghidra 插件）", "axt @addr  引用", "izz  字符串",
        "s addr  跳转", "px 64 @addr  hexdump", "aaa  全量分析",
    )

    Column(
        Modifier.fillMaxSize().padding(10.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ── 引擎状态 ──
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Memory, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(if (zh) "Rizin 引擎（内置）" else "Rizin engine (built-in)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text("OK", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            if (zh) "塔菲逆核内置 Rizin（radare2 系）引擎，所有 taffy_so_* / taffy_analyze_* / taffy_rz 工具均由它驱动，完全离线。"
            else "TaffyNiHe embeds a Rizin (radare2-family) engine; all taffy_so_* / taffy_analyze_* / taffy_rz tools are powered by it, fully offline.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ── 内置工具路由 ──
        Text(if (zh) "内置 rz 工具（MCP 路由）" else "Built-in rz tools (MCP routing)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
        Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))) {
            rootTools.forEachIndexed { i, (name, desc) ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(name, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp), color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(190.dp))
                    Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // ── 常用 rz 命令速查 ──
        Text(if (zh) "常用 rz 命令速查" else "Quick rz commands", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
        SelectionContainer {
            Text(
                quickCommands.joinToString("\n"),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)).padding(12.dp),
            )
        }

        Text(
            if (zh) "提示：SO 分析统一走 taffy_so_open → analyze_* / edit_* → taffy_build_so 流程；taffy_rz (action=decompile) 使用 rizin-ghidra 插件把函数反编译为类 C 伪代码；DEX/APK 反编译用 taffy_jadx_decompile。"
            else "Tip: use taffy_so_open → analyze_* / edit_* → taffy_build_so for SO analysis; taffy_rz (action=decompile) emits pseudo-C via rizin-ghidra; use taffy_jadx_decompile for DEX/APK.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
}
