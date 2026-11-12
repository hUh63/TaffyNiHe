package com.soreverse.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soreverse.mcp.core.EngineProvider
import com.soreverse.mcp.core.SettingsStore
import com.soreverse.mcp.core.safeLaunch
import com.soreverse.mcp.mcp.ToolCatalog
import com.soreverse.mcp.mcp.ToolContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Frida 工具包（内嵌在「逆向分析 → Frida」页签里，不再多一层目录）。
 *
 * 三块东西放在一页，因为它们是同一条链上的前后步骤：
 *  ① gadget 版本库：挑版本、按 ABI 下载（官方 .xz 自动解压 + ELF 校验）；
 *  ② 注入点扫描：这个 APK 到底能往哪塞代码（Application / launcher / 带 JNI_OnLoad 的 so）；
 *  ③ 反分析扫描：塞之前先看清目标有没有反调试 / Hook 检测 / 加固。
 */
@Composable
internal fun SettingsFridaKitPage(t: UiText) {
    val zh = t.zh
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var libStatus by remember { mutableStateOf<JSONObject?>(null) }
    var versionList by remember { mutableStateOf<JSONArray?>(null) }
    var pointResult by remember { mutableStateOf<JSONObject?>(null) }
    var guardResult by remember { mutableStateOf<JSONObject?>(null) }
    var targetPath by rememberSaveable { mutableStateOf("") }
    var importPath by rememberSaveable { mutableStateOf("") }
    var decodeDir by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    suspend fun callTool(name: String, args: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            val h = ToolCatalog.byName[name] ?: return@runCatching null
            val tcx = ToolContext(context, SettingsStore(context), EngineProvider.get(context), null)
            h.handle(tcx, args)
        }.getOrNull()
    }

    fun refreshStatus() {
        scope.safeLaunch {
            busy = "status"
            val r = callTool("taffy_frida_gadget_kit", JSONObject().put("action", "status"))
            libStatus = r
            busy = ""
            if (r == null) message = if (zh) "读取本地库失败" else "Failed to read local library"
        }
    }

    // 进页面先把本地库读出来（只读，开销很小）
    remember(Unit) {
        refreshStatus()
        true
    }

    PageScroll {
        // ───────── ① gadget 版本库 ─────────
        GlassGroup(
            title = if (zh) "Gadget 版本库" else "Gadget library",
            footer = if (zh) "官方只发布 .so.xz：下载后自动解压并校验 ELF 架构，架构不符会当场拒绝，不会把错架构的 gadget 装进包里。"
            else "Official assets are .so.xz: downloaded, unpacked and ELF-checked; wrong-arch gadget is rejected.",
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val store = libStatus
                if (store == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(if (zh) "读取中…" else "Loading…", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    val libs = store.optJSONArray("libraries") ?: JSONArray()
                    Text(
                        if (libs.length() == 0) {
                            if (zh) "本地还没有 gadget" else "No gadget stored yet"
                        } else {
                            if (zh) "本地 ${libs.length()} 份 · 共 ${fmtSize(store.optLong("totalBytes"))}"
                            else "${libs.length()} gadget(s) · ${fmtSize(store.optLong("totalBytes"))}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    for (i in 0 until libs.length()) {
                        val lib = libs.optJSONObject(i) ?: continue
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                "${lib.optString("version")}  ·  ${lib.optString("abi")}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                fmtSize(lib.optLong("size")),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolChip(
                        label = if (zh) "刷新版本列表" else "Refresh versions",
                        icon = Icons.Filled.Refresh,
                        enabled = busy.isEmpty(),
                        onClick = {
                            scope.safeLaunch {
                                busy = "versions"
                                val r = callTool("taffy_frida_gadget_kit", JSONObject().put("action", "versions").put("force", "true").put("limit", 12))
                                versionList = r?.optJSONArray("versions")
                                busy = ""
                                message = if (r?.optBoolean("ok") == true) {
                                    if (zh) "拿到 ${r.optInt("count")} 个版本" else "Got ${r.optInt("count")} versions"
                                } else {
                                    r?.optJSONObject("error")?.optString("message") ?: (if (zh) "拉取失败" else "Fetch failed")
                                }
                            }
                        },
                    )
                    ToolChip(
                        label = if (zh) "下载 arm64-v8a" else "Download arm64-v8a",
                        icon = Icons.Filled.Download,
                        accent = true,
                        enabled = busy.isEmpty(),
                        onClick = {
                            scope.safeLaunch {
                                busy = "download"
                                val r = callTool(
                                    "taffy_frida_gadget_kit",
                                    JSONObject().put("action", "download").put("version", "latest")
                                        .put("abis", JSONArray().put("arm64-v8a")),
                                )
                                busy = ""
                                message = if (r?.optBoolean("ok") == true) {
                                    if (zh) "已下载 ${r.optString("version")}" else "Downloaded ${r.optString("version")}"
                                } else {
                                    r?.optJSONObject("error")?.optString("message") ?: (if (zh) "下载失败" else "Download failed")
                                }
                                refreshStatus()
                            }
                        },
                    )
                }

                val versions = versionList
                if (versions != null && versions.length() > 0) {
                    Text(
                        if (zh) "点一个版本直接下载（arm64-v8a）" else "Tap a version to download (arm64-v8a)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    for (i in 0 until versions.length()) {
                        val v = versions.optJSONObject(i) ?: continue
                        val ver = v.optString("version")
                        val pre = v.optBoolean("prerelease")
                        DataRow(
                            title = ver + if (pre) "  (pre)" else "",
                            subtitle = v.optString("publishedAt").take(10) + "  ·  " + abisText(v.optJSONArray("abis")),
                            onClick = {
                                if (busy.isEmpty()) scope.safeLaunch {
                                    busy = "download"
                                    val r = callTool(
                                        "taffy_frida_gadget_kit",
                                        JSONObject().put("action", "download").put("version", ver)
                                            .put("abis", JSONArray().put("arm64-v8a")),
                                    )
                                    busy = ""
                                    message = if (r?.optBoolean("ok") == true) {
                                        if (zh) "已下载 $ver" else "Downloaded $ver"
                                    } else {
                                        r?.optJSONObject("error")?.optString("message") ?: (if (zh) "下载失败" else "Download failed")
                                    }
                                    refreshStatus()
                                }
                            },
                        )
                    }
                }

                OutlinedTextField(
                    value = importPath,
                    onValueChange = { importPath = it },
                    label = { Text(if (zh) "导入本地 gadget（.so 或 .xz 的完整路径）" else "Import local gadget (.so / .xz absolute path)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ToolChip(
                    label = if (zh) "导入到库" else "Import",
                    enabled = busy.isEmpty() && importPath.isNotBlank(),
                    onClick = {
                        scope.safeLaunch {
                            busy = "import"
                            val r = callTool(
                                "taffy_frida_gadget_kit",
                                JSONObject().put("action", "import").put("path", importPath.trim()),
                            )
                            busy = ""
                            message = if (r?.optBoolean("ok") == true) {
                                if (zh) "已导入 ${r.optString("abi")}" else "Imported ${r.optString("abi")}"
                            } else {
                                r?.optJSONObject("error")?.optString("message") ?: (if (zh) "导入失败" else "Import failed")
                            }
                            refreshStatus()
                        }
                    },
                )

                OutlinedTextField(
                    value = decodeDir,
                    onValueChange = { decodeDir = it },
                    label = { Text(if (zh) "decode 目录（放置目标，可留空）" else "decode dir (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ToolChip(
                    label = if (zh) "放进 decode 目录" else "Place into decode dir",
                    enabled = busy.isEmpty() && decodeDir.isNotBlank(),
                    onClick = {
                        scope.safeLaunch {
                            busy = "place"
                            val r = callTool(
                                "taffy_frida_gadget_kit",
                                JSONObject().put("action", "place").put("abi", "arm64-v8a").put("dir", decodeDir.trim()),
                            )
                            busy = ""
                            message = if (r?.optBoolean("ok") == true) {
                                if (zh) "已放置到 ${r.optString("path")}" else "Placed to ${r.optString("path")}"
                            } else {
                                r?.optJSONObject("error")?.optString("message") ?: (if (zh) "放置失败" else "Place failed")
                            }
                        }
                    },
                )
            }
        }

        // ───────── ② 注入点扫描 ─────────
        GlassGroup(
            title = if (zh) "注入点扫描" else "Injection points",
            footer = if (zh) "只读扫描：列出 Application / launcher Activity / 带 JNI_OnLoad 的 so / 可 hook 的 JNI 函数，并告诉你下一步该用哪个工具。"
            else "Read-only: Application, launcher, native libs with JNI_OnLoad, hookable JNI functions + the next tool to use.",
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = targetPath,
                    onValueChange = { targetPath = it },
                    label = { Text(if (zh) "APK 或 decode 目录路径" else "APK or decode dir path") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ToolChip(
                    label = if (zh) "扫描注入点" else "Scan points",
                    icon = Icons.Filled.Search,
                    enabled = busy.isEmpty() && targetPath.isNotBlank(),
                    onClick = {
                        scope.safeLaunch {
                            busy = "points"
                            pointResult = callTool(
                                "taffy_apk_inject_points",
                                JSONObject().put("path", targetPath.trim()).put("maxJni", 8),
                            )
                            busy = ""
                        }
                    },
                )
                val pr = pointResult
                if (pr != null) {
                    if (pr.optBoolean("ok") != true) {
                        Text(
                            pr.optJSONObject("error")?.optString("message") ?: (if (zh) "扫描失败" else "Scan failed"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        MonoRow(if (zh) "包名" else "Package", pr.optString("packageName"))
                        MonoRow("Application", pr.optString("applicationClass").ifBlank { "-" })
                        MonoRow(if (zh) "入口 Activity" else "Launcher", pr.optString("launcherActivity").ifBlank { "-" })
                        MonoRow("debuggable", pr.optBoolean("debuggable").toString())
                        MonoRow("ABI", abisText(pr.optJSONArray("abis")))
                        val pts = pr.optJSONArray("points") ?: JSONArray()
                        Text(
                            if (zh) "${pts.length()} 个注入点" else "${pts.length()} point(s)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        for (i in 0 until pts.length()) {
                            val p = pts.optJSONObject(i) ?: continue
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    "${p.optString("kind")}  ${p.optString("target")}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Text(
                                    p.optString("why"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "→ " + p.optString("how"),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        val rec = pr.optJSONArray("recommend") ?: JSONArray()
                        for (i in 0 until rec.length()) {
                            Text("• " + rec.optString(i), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        // ───────── ③ 反分析扫描 ─────────
        GlassGroup(
            title = if (zh) "反分析扫描" else "Guard scan",
            footer = if (zh) "塞代码之前先看清对手：签名/完整性校验、反调试、模拟器、Frida·Xposed 检测、Root、VMP、加固厂商、资源加密、类名混淆度。"
            else "Know the opposition before injecting: signature/integrity checks, anti-debug, emulator, Frida/Xposed detection, root, VMP, packers, encrypted resources, obfuscation.",
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ToolChip(
                    label = if (zh) "用上面的路径扫描" else "Scan the path above",
                    icon = Icons.Filled.Security,
                    enabled = busy.isEmpty() && targetPath.isNotBlank(),
                    onClick = {
                        scope.safeLaunch {
                            busy = "guard"
                            guardResult = callTool("taffy_guard_scan", JSONObject().put("path", targetPath.trim()))
                            busy = ""
                        }
                    },
                )
                val gr = guardResult
                if (gr != null) {
                    if (gr.optBoolean("ok") != true) {
                        Text(
                            gr.optJSONObject("error")?.optString("message") ?: (if (zh) "扫描失败" else "Scan failed"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        val packers = gr.optJSONArray("packers") ?: JSONArray()
                        if (packers.length() > 0) {
                            val names = ArrayList<String>()
                            for (i in 0 until packers.length()) {
                                names.add(packers.optJSONObject(i)?.optString("name") ?: "")
                            }
                            Text(
                                (if (zh) "加固：" else "Packers: ") + names.joinToString(" / "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        val cats = gr.optJSONObject("categories")
                        if (cats != null && cats.length() > 0) {
                            val keys = cats.keys()
                            val parts = ArrayList<String>()
                            while (keys.hasNext()) {
                                val k = keys.next()
                                parts.add("$k×${cats.optInt(k)}")
                            }
                            Text(parts.joinToString("  "), style = MaterialTheme.typography.labelMedium)
                        } else if (packers.length() == 0) {
                            Text(
                                if (zh) "没有命中已知的反分析特征" else "No known guard signatures",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val obf = gr.optJSONObject("obfuscation")
                        if (obf != null) {
                            MonoRow(
                                if (zh) "混淆度" else "Obfuscation",
                                "${obf.optInt("score")}/100 (${obf.optString("level")})  " +
                                    (if (zh) "短名 ${pct(obf.optDouble("shortNameRatio"))}" else "short ${pct(obf.optDouble("shortNameRatio"))}"),
                            )
                        }
                        val findings = gr.optJSONArray("findings") ?: JSONArray()
                        for (i in 0 until minOf(findings.length(), 24)) {
                            val f = findings.optJSONObject(i) ?: continue
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    "[${f.optString("label")}] ${f.optString("evidence")}  @ ${f.optString("where")}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Text(
                                    f.optString("advice"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        val advice = gr.optJSONArray("advice") ?: JSONArray()
                        for (i in 0 until advice.length()) {
                            Text("• " + advice.optString(i), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        if (message.isNotBlank()) {
            Text(
                message,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (busy.isNotEmpty()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.width(14.dp).height(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    when (busy) {
                        "status" -> if (zh) "读取本地库…" else "Reading library…"
                        "versions" -> if (zh) "拉取版本列表…" else "Fetching versions…"
                        "download" -> if (zh) "下载并解压…" else "Downloading…"
                        "import" -> if (zh) "导入…" else "Importing…"
                        "place" -> if (zh) "放置…" else "Placing…"
                        "points" -> if (zh) "扫描注入点…" else "Scanning points…"
                        else -> if (zh) "扫描…" else "Scanning…"
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

private fun abisText(arr: JSONArray?): String {
    if (arr == null || arr.length() == 0) return "-"
    val parts = ArrayList<String>()
    for (i in 0 until arr.length()) parts.add(arr.optString(i))
    return parts.joinToString(", ")
}

private fun fmtSize(v: Long): String {
    if (v <= 0) return "-"
    val mb = v / 1024.0 / 1024.0
    return if (mb >= 1) String.format("%.1f MB", mb) else String.format("%d KB", v / 1024)
}

private fun pct(v: Double): String = String.format("%.0f%%", v * 100)
