package com.soreverse.mcp

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import com.soreverse.mcp.core.ElfSections
import com.soreverse.mcp.core.runCatchingCancellable
import com.soreverse.mcp.core.safeLaunch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 地址查看器的「节区」下拉：点一下就跳到该节区的文件偏移。
 *
 * 之前只能手输 offset —— 想知道 `.text` 在哪、或者 `libil2cpp.so` 的 `.rodata` 从哪开始，
 * 得先去外部工具里算十六进制。这里直接在页内列出节区名 / 类型 / 偏移。
 *
 * 独立成文件而不是塞进 AnalysisWorkspace：那个文件有 arch-policy 行数基线，
 * 这边只留一行调用点。
 */
@Composable
internal fun AddrSectionsMenu(
    context: android.content.Context,
    path: String,
    zh: Boolean,
    onPick: (offset: Long) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<ElfSections.Item>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // 换了文件就要重新读；不预加载（用户不点就不读盘）。
    LaunchedEffect(path) {
        items = emptyList()
        error = ""
        loading = false
    }

    fun loadIfNeeded() {
        if (loading || items.isNotEmpty() || error.isNotEmpty()) return
        val p = path.trim()
        if (p.isEmpty()) {
            error = if (zh) "先选择文件" else "pick a file first"
            return
        }
        loading = true
        scope.safeLaunch {
            val list = withContext(Dispatchers.IO) {
                runCatchingCancellable { ElfSections.read(context, p) }.getOrElse { emptyList() }
            }
            items = list
            if (list.isEmpty()) error = if (zh) "不是 ELF，或没有可读的节区表" else "not ELF / no section table"
            loading = false
        }
    }

    TextButton(onClick = {
        open = true
        loadIfNeeded()
    }) {
        Text(if (zh) "节区" else "Sections", fontSize = AppText.label)
    }

    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        when {
            loading -> DropdownMenuItem(text = { Text("…", fontSize = AppText.label) }, onClick = {})
            error.isNotBlank() -> DropdownMenuItem(
                text = { Text(error, fontSize = AppText.label, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                onClick = { open = false },
            )
            else -> items.forEachIndexed { i, sec ->
                // 节区表通常几十项，超长表只展示前 200 个（再往下用户也不会在菜单里翻）。
                if (i >= 200) return@forEachIndexed
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                sec.name.ifBlank { "<$i>" },
                                fontSize = AppText.label,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                sec.typeLabel() + "  @0x" + java.lang.Long.toHexString(sec.offset) +
                                    if (sec.isExecutable) "  X" else if (sec.isAlloc) "  A" else "",
                                fontSize = AppText.label,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        onPick(sec.offset)
                        open = false
                    },
                )
            }
        }
    }
}
