package com.soreverse.mcp

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soreverse.mcp.core.safeLaunch
import com.soreverse.mcp.engine.DexIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * DEX 索引查询（分析页视图，view key = `dexindex`）。
 *
 * 定位：分析页原来只服务 SO。这里给 APK/DEX 一个入口 —— 一次遍历回答
 * 「谁 new 了这个类 / 谁读写了这个字段 / 谁调用了这个方法 / 谁重写了它」，
 * 以及按字符串、按整数常量、按 smali 片段反查。
 *
 * 刻意不做「原始 JSON / 文本」兜底出口：结果列表本身就是为这种数据设计的
 * （类名 → 成员 → 命中细节 → 所在 dex），点一行可复制签名。
 */
@Composable
internal fun DexIndexView(zh: Boolean, context: android.content.Context) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current

    var srcFile by remember { mutableStateOf<File?>(null) }
    var srcName by remember { mutableStateOf("") }
    var srcSize by remember { mutableLongStateOf(0L) }
    var fileError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    var type by rememberSaveable { mutableStateOf("class") }
    var keyword by rememberSaveable { mutableStateOf("") }
    var exclude by rememberSaveable { mutableStateOf("") }
    var showExclude by rememberSaveable { mutableStateOf(false) }
    var regex by rememberSaveable { mutableStateOf(false) }
    var caseSensitive by rememberSaveable { mutableStateOf(false) }
    var typeMenu by remember { mutableStateOf(false) }

    var busy by remember { mutableStateOf(false) }
    var hits by remember { mutableStateOf<List<DexIndex.Hit>>(emptyList()) }
    var matched by remember { mutableIntStateOf(0) }
    var scannedClasses by remember { mutableIntStateOf(0) }
    var scannedMethods by remember { mutableIntStateOf(0) }
    var scannedInsns by remember { mutableLongStateOf(0L) }
    var dexCount by remember { mutableIntStateOf(0) }
    var queryError by remember { mutableStateOf<String?>(null) }
    var searched by remember { mutableStateOf(false) }
    // 上一次查询的指纹：改完类型/关键字再点「继续加载」时，不能把两次查询的结果拼在一起。
    var lastKey by remember { mutableStateOf("") }

    val typeLabel = DexIndex.TYPE_LABELS_ZH[type] ?: type
    val needKeyword = type in DexIndex.KEYWORD_REQUIRED

    fun copyToCache(uri: Uri, name: String) {
        loading = true
        fileError = null
        scope.safeLaunch {
            val out = withContext(Dispatchers.IO) {
                runCatching {
                    val dst = File(context.cacheDir, "dexindex_src_${System.nanoTime()}.bin")
                    val limit = 512L * 1024 * 1024
                    var total = 0L
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        dst.outputStream().use { o ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                total += n
                                if (total > limit) error("文件超过 512 MiB")
                                o.write(buf, 0, n)
                            }
                        }
                    } ?: error("无法打开文件")
                    dst to total
                }
            }
            loading = false
            out.getOrNull()?.let { (f, size) ->
                srcFile?.delete()
                srcFile = f
                srcName = name
                srcSize = size
                hits = emptyList(); matched = 0; searched = false; queryError = null; lastKey = ""
            } ?: run { fileError = out.exceptionOrNull()?.message ?: (if (zh) "读取失败" else "Read failed") }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "source"
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0) c.getString(ni)?.let { name = it }
                }
            }
        }
        copyToCache(uri, name)
    }

    fun runSearch(append: Boolean) {
        val f = srcFile ?: return
        val key = "$type$keyword$exclude$regex$caseSensitive"
        val realAppend = append && key == lastKey
        lastKey = key
        busy = true
        queryError = null
        val offset = if (realAppend) hits.size else 0
        scope.safeLaunch {
            val r = withContext(Dispatchers.IO) {
                DexIndex.search(
                    path = f.absolutePath,
                    type = type,
                    keyword = keyword,
                    excludePackages = exclude.split(',', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() },
                    caseSensitive = caseSensitive,
                    regex = regex,
                    limit = 200,
                    offset = offset,
                    tmpDir = File(context.cacheDir, "dexindex"),
                )
            }
            busy = false
            searched = true
            if (r.error != null) {
                queryError = r.error
                if (!realAppend) { hits = emptyList(); matched = 0 }
            } else {
                hits = if (realAppend) hits + r.hits else r.hits
                matched = r.matched
                scannedClasses = r.scannedClasses
                scannedMethods = r.scannedMethods
                scannedInsns = r.scannedInstructions
                dexCount = r.dexCount
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
        // ---------------- 文件条
        Surface(
            color = cs.surfaceContainerHigh,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(AppShape.md),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        ) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.size(18.dp), tint = cs.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            loading -> if (zh) "读取中…" else "Reading…"
                            srcFile != null -> srcName
                            else -> if (zh) "未选择 APK / DEX" else "No APK / DEX selected"
                        },
                        fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontFamily = if (srcFile != null) FontFamily.Monospace else null,
                        color = if (srcFile != null) cs.onSurface else cs.onSurfaceVariant,
                    )
                    val sub = when {
                        srcFile != null && srcSize > 0 -> "${srcSize / 1024 / 1024} MiB · ${srcFile!!.absolutePath.substringAfterLast('/')}"
                        fileError != null -> fileError!!
                        else -> if (zh) "选一个 APK 或 DEX" else "Pick an APK or DEX"
                    }
                    Text(
                        sub, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (fileError != null) cs.error else cs.onSurfaceVariant,
                    )
                }
                if (srcFile != null) {
                    IconButton(onClick = {
                        srcFile?.delete(); srcFile = null; srcName = ""; srcSize = 0
                        hits = emptyList(); matched = 0; searched = false; lastKey = ""
                    }, enabled = !busy && !loading, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Clear, null, modifier = Modifier.size(16.dp), tint = cs.onSurfaceVariant)
                    }
                }
                TextButton(onClick = {
                    runCatching { picker.launch(arrayOf("*/*")) }
                }, enabled = !loading && !busy) { Text(if (zh) "选择" else "Pick", fontSize = 12.sp) }
            }
        }

        // ---------------- 查询条件
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                OutlinedButton(
                    onClick = { typeMenu = true },
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) { Text(typeLabel, fontSize = 12.sp) }
                DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                    for ((k, v) in DexIndex.TYPE_LABELS_ZH) {
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(v, fontSize = 13.sp)
                                    if (k !in DexIndex.KEYWORD_REQUIRED) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            if (zh) "可空" else "any",
                                            fontSize = 9.sp, color = cs.primary,
                                        )
                                    }
                                }
                            },
                            onClick = { type = k; typeMenu = false },
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                placeholder = {
                    Text(
                        if (needKeyword) {
                            when (type) {
                                "int" -> if (zh) "整数，如 0x7f0a0012" else "int, e.g. 0x7f0a0012"
                                "code" -> if (zh) "smali 片段" else "smali snippet"
                                else -> if (zh) "关键字" else "keyword"
                            }
                        } else if (zh) "留空 = 列全部" else "empty = list all",
                        fontSize = 12.sp,
                    )
                },
            )
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                onClick = { runSearch(false) },
                enabled = srcFile != null && !busy && (!needKeyword || keyword.isNotBlank()),
                modifier = Modifier.height(40.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Search, null, modifier = Modifier.size(16.dp))
                }
            }
        }

        // ---------------- 选项行
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = regex,
                onClick = { regex = !regex },
                label = { Text(".*", fontSize = 11.sp, fontFamily = FontFamily.Monospace) },
                modifier = Modifier.height(30.dp),
            )
            FilterChip(
                selected = caseSensitive,
                onClick = { caseSensitive = !caseSensitive },
                label = { Text("Aa", fontSize = 11.sp) },
                modifier = Modifier.height(30.dp),
            )
            FilterChip(
                selected = showExclude || exclude.isNotBlank(),
                onClick = { showExclude = !showExclude },
                label = { Text(if (zh) "排除包" else "Exclude", fontSize = 11.sp) },
                modifier = Modifier.height(30.dp),
            )
            Spacer(Modifier.weight(1f))
            if (searched) {
                Text(
                    "$matched",
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = cs.primary,
                )
            }
        }

        if (showExclude) {
            OutlinedTextField(
                value = exclude,
                onValueChange = { exclude = it },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                placeholder = { Text("com.android.,kotlin.", fontSize = 12.sp) },
            )
        }

        // ---------------- 状态行
        if (queryError != null) {
            Text(
                queryError!!, fontSize = 11.sp, color = cs.error,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        } else if (searched) {
            Text(
                buildString {
                    append(if (zh) "命中 " else "hits ")
                    append(matched)
                    append(" · ")
                    append(if (zh) "已返回 " else "returned ")
                    append(hits.size)
                    append(" · ")
                    if (zh) {
                        append("扫描 $dexCount dex / $scannedClasses 类 / $scannedMethods 方法 / $scannedInsns 指令")
                    } else {
                        append("$dexCount dex / $scannedClasses classes / $scannedMethods methods / $scannedInsns insns")
                    }
                },
                fontSize = 10.sp, color = cs.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
            )
        }

        // ---------------- 结果
        if (hits.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(bottom = 24.dp), contentAlignment = Alignment.Center) {
                Text(
                    when {
                        busy -> if (zh) "扫描中…" else "Scanning…"
                        !searched -> if (zh) "选好类型与关键字后点搜索" else "Pick a type and keyword, then search"
                        else -> if (zh) "没有命中" else "No hits"
                    },
                    fontSize = 12.sp, color = cs.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(hits) { idx, h ->
                    Column(
                        Modifier.fillMaxWidth()
                            .padding(vertical = 1.dp)
                            .clickable {
                                clip.setText(AnnotatedString(h.member.ifBlank { h.className }))
                            }
                            .background(cs.surfaceContainerHigh, androidx.compose.foundation.shape.RoundedCornerShape(AppShape.sm))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${idx + 1}",
                                fontSize = 9.sp, color = cs.onSurfaceVariant,
                                modifier = Modifier.width(28.dp),
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                h.className,
                                fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                h.dex.removeSuffix(".dex"),
                                fontSize = 9.sp, color = cs.onSurfaceVariant,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                        Text(
                            h.member,
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                            color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 28.dp),
                        )
                        Text(
                            h.detail,
                            fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                            color = cs.primary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 28.dp),
                        )
                    }
                }
                if (matched > hits.size) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            OutlinedButton(
                                onClick = { runSearch(true) },
                                enabled = !busy,
                                modifier = Modifier.height(34.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp),
                            ) {
                                Text(
                                    if (zh) "继续加载（${hits.size}/$matched）" else "Load more (${hits.size}/$matched)",
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}
