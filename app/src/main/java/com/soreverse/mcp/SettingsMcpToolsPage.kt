package com.soreverse.mcp

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.soreverse.mcp.core.SettingsStore
import com.soreverse.mcp.core.SigningKeyStore
import com.soreverse.mcp.core.TempWorkspaceManager
import com.soreverse.mcp.core.WorkspacePolicy
import java.io.File

// ─────────────────────────── APK 签名设置 ───────────────────────────

private val SCHEME_OPTIONS = listOf(
    "v1v2v3" to "V1+V2+V3",
    "v1v2" to "V1+V2",
    "v1v3" to "V1+V3",
    "v1" to "V1",
    "v2v3" to "V2+V3 (Android 7.0+)",
    "v2" to "V2 (Android 7.0+)",
    "v3" to "V3 (Android 9.0+)",
)

@Composable
internal fun SettingsApkSignPage(t: UiText, settings: SettingsStore) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var showImport by remember { mutableStateOf(false) }
    var showManage by remember { mutableStateOf(false) }
    // 触发重组
    @Suppress("UNUSED_EXPRESSION") refresh
    val keySource = settings.apkSignKeySource
    val activeKey = settings.apkSignKeystoreName

    PageScroll {
        GlassGroup(title = if (t.zh) "自动签名" else "Auto sign") {
            ToggleRow(
                if (t.zh) "修改APK后自动签名" else "Auto-sign after APK edit",
                settings.apkAutoSign,
            ) { settings.apkAutoSign = it; refresh++ }
        }

        GlassGroup(title = if (t.zh) "签名密钥" else "Signing key") {
            Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KeySourceOption(if (t.zh) "默认" else "Default", keySource != "custom", AppPalette.indigo, Modifier.weight(1f)) {
                    settings.apkSignKeySource = "default"; refresh++
                }
                KeySourceOption(if (t.zh) "其他密钥" else "Custom", keySource == "custom", AppPalette.orange, Modifier.weight(1f)) {
                    settings.apkSignKeySource = "custom"; refresh++
                }
            }
            if (keySource == "custom") {
                GroupDivider()
                NavRow(
                    title = if (activeKey.isBlank()) if (t.zh) "未选择密钥" else "No key selected" else activeKey,
                    subtitle = if (t.zh) "导入 keystore 或管理密钥库" else "Import or manage keystores",
                    icon = Icons.Default.Key,
                    onClick = { showManage = true },
                )
                Row(Modifier.padding(14.dp)) {
                    PrimaryActionButton(if (t.zh) "导入密钥" else "Import key", { showImport = true }, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        GlassGroup(title = if (t.zh) "签名方案" else "Signing scheme") {
            SCHEME_OPTIONS.forEachIndexed { idx, (code, label) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { settings.apkSignScheme = code; refresh++ }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = settings.apkSignScheme == code, onClick = { settings.apkSignScheme = code; refresh++ })
                    Text(label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                }
                if (idx != SCHEME_OPTIONS.lastIndex) GroupDivider()
            }
        }

        GlassGroup(title = if (t.zh) "V1 签名" else "V1 signing") {
            ToggleRow(
                if (t.zh) "自定义 V1 签名数据文件名" else "Custom V1 signer name",
                settings.apkV1SignerEnabled,
            ) { settings.apkV1SignerEnabled = it; refresh++ }
            if (settings.apkV1SignerEnabled) {
                GroupDivider()
                var v1Name by remember { mutableStateOf(settings.apkV1SignerName) }
                Column(Modifier.padding(14.dp)) {
                    OutlinedTextField(
                        value = v1Name,
                        onValueChange = { v1Name = it; settings.apkV1SignerName = it },
                        label = { Text(if (t.zh) "V1 签名数据文件名" else "V1 signer file name") },
                        supportingText = { Text(if (t.zh) "自定义 V1 签名产生的 RSA/SF 文件的文件名，若留空则自动从签名密钥中获取数据" else "File name of the V1 .RSA/.SF files. If empty, derived from the signing key automatically.") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            GroupDivider()
            ToggleRow(
                if (t.zh) "不签名时保留 V2/V3 签名数据" else "Keep V2/V3 signature when unsigned",
                settings.apkKeepV2V3WhenNoSign,
            ) { settings.apkKeepV2V3WhenNoSign = it; refresh++ }
        }
    }

    if (showImport) ImportKeyDialog(t, settings, onDismiss = { showImport = false })
    if (showManage) ManageKeysDialog(t, settings, onDismiss = { showManage = false })
}

@Composable
private fun KeySourceOption(label: String, selected: Boolean, tint: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .clip(shape)
            .background(if (selected) tint.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(1.dp, if (selected) tint.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), shape)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.CheckCircle, null, tint = if (selected) tint else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.padding(end = 6.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
    }
}

@Composable
private fun ImportKeyDialog(t: UiText, settings: SettingsStore, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var alias by remember { mutableStateOf("") }
    var storePass by remember { mutableStateOf("") }
    var keyPass by remember { mutableStateOf("") }
    var pickedPath by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            // content:// 复制到 cacheDir 临时文件，供 SigningKeyStore 导入
            val copied = runCatching {
                val name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "imported-keystore"
                val target = File(context.cacheDir, "keystore-import-$name")
                context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                target.absolutePath
            }.getOrElse { e -> error = e.message ?: "读取文件失败"; "" }
            if (copied.isNotBlank()) pickedPath = copied
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (t.zh) "导入签名密钥" else "Import signing key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                NavRow(
                    title = if (pickedPath.isBlank()) if (t.zh) "选择 keystore 文件" else "Pick keystore file" else File(pickedPath).name,
                    subtitle = if (pickedPath.isBlank()) if (t.zh) "支持 .jks / .p12" else ".jks / .p12" else pickedPath,
                    icon = Icons.Default.FolderOpen,
                    onClick = { picker.launch(arrayOf("*/*")) },
                )
                OutlinedTextField(alias, { alias = it }, label = { Text(if (t.zh) "密钥别名 (alias)" else "Alias") }, singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(storePass, { storePass = it }, label = { Text(if (t.zh) "密钥库口令 (store pass)" else "Store pass") }, singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(keyPass, { keyPass = it }, label = { Text(if (t.zh) "密钥口令 (key pass, 可选)" else "Key pass (optional)") }, singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton({
                if (pickedPath.isBlank()) { error = if (t.zh) "请先选择 keystore 文件" else "Pick a keystore file first"; return@TextButton }
                if (alias.isBlank()) { error = if (t.zh) "请填写密钥别名" else "Alias is required"; return@TextButton }
                if (storePass.isBlank()) { error = if (t.zh) "请填写密钥库口令" else "Store pass is required"; return@TextButton }
                val result = SigningKeyStore.import(context, pickedPath, alias.trim(), storePass, keyPass.takeIf { it.isNotBlank() })
                if (result.optBoolean("ok", false)) {
                    settings.apkSignKeystoreName = result.optString("name")
                    settings.apkSignKeySource = "custom"
                    Toast.makeText(context, if (t.zh) "密钥导入成功" else "Key imported", Toast.LENGTH_SHORT).show()
                    onDismiss()
                } else {
                    error = result.optJSONObject("error")?.optString("message") ?: if (t.zh) "导入失败" else "Import failed"
                }
            }) { Text(if (t.zh) "导入" else "Import") }
        },
        dismissButton = { TextButton(onDismiss) { Text(if (t.zh) "取消" else "Cancel") } },
    )
}

@Composable
private fun ManageKeysDialog(t: UiText, settings: SettingsStore, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_EXPRESSION") refresh
    val keys = SigningKeyStore.list(context)
    val active = settings.apkSignKeystoreName

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (t.zh) "密钥管理" else "Key manager") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (keys.length() == 0) {
                    Text(if (t.zh) "暂无已导入密钥" else "No imported keys", style = MaterialTheme.typography.bodySmall)
                }
                for (i in 0 until keys.length()) {
                    val k = keys.getJSONObject(i)
                    val name = k.optString("name")
                    val isActive = name == active
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)).padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                            Text("${k.optString("alias")}  ·  ${k.optString("subject")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                        TextButton({
                            settings.apkSignKeystoreName = name
                            settings.apkSignKeySource = "custom"
                            refresh++
                        }) { Text(if (t.zh) "设为当前" else "Use") }
                        IconButtonMini({ SigningKeyStore.delete(context, name); refresh++ }, Icons.Default.Delete, t)
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(if (t.zh) "完成" else "Done") } },
    )
}

@Composable
private fun IconButtonMini(onClick: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector, t: UiText) {
    androidx.compose.material3.IconButton(onClick = onClick) {
        Icon(icon, if (t.zh) "删除" else "Delete", tint = MaterialTheme.colorScheme.error)
    }
}

// ─────────────────────────── 临时工作区 ───────────────────────────

@Composable
internal fun SettingsTempWorkspacePage(t: UiText, settings: SettingsStore) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var limit by remember { mutableIntStateOf(settings.tempWorkspaceLimit) }
    var showCleanConfirm by remember { mutableStateOf(false) }
    @Suppress("UNUSED_EXPRESSION") refresh
    val count = TempWorkspaceManager.count(context)
    val stats = TempWorkspaceManager.stats(context)

    PageScroll {
        GlassGroup(title = if (t.zh) "临时工作区数量上限" else "Temp workspace limit") {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (t.zh) "每类临时工作区最多保留 $limit 个，超出时自动删除最旧的"
                    else "Keep at most $limit per category; the oldest are pruned automatically",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = limit.toFloat(),
                    onValueChange = { limit = it.toInt() },
                    onValueChangeFinished = { settings.tempWorkspaceLimit = limit; TempWorkspaceManager.pruneToLimit(context); refresh++ },
                    valueRange = 1f..100f,
                    steps = 98,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("1", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("100", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        GlassGroup(title = if (t.zh) "当前占用" else "Current usage") {
            NavRow(
                title = if (t.zh) "$count 个临时工作区" else "$count temp workspaces",
                subtitle = if (t.zh) "smali-batch / apkeditor-out / extracted / jadx-out 等" else "smali-batch / apkeditor-out / extracted / jadx-out etc.",
                icon = Icons.Default.Storage,
                onClick = {},
            )
            for (i in 0 until stats.length()) {
                val s = stats.getJSONObject(i)
                if (s.optInt("itemCount") <= 0) continue
                GroupDivider()
                NavRow(
                    title = s.optString("name"),
                    subtitle = if (t.zh) "${s.optInt("itemCount")} 项 · ${s.optLong("bytes") / 1024 / 1024} MB" else "${s.optInt("itemCount")} items · ${s.optLong("bytes") / 1024 / 1024} MB",
                    icon = Icons.Default.FolderOpen,
                    onClick = {},
                )
            }
        }

        GlassGroup(title = if (t.zh) "清理" else "Cleanup") {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryActionButton(if (t.zh) "按上限裁剪最旧工作区" else "Prune oldest to limit", {
                    TempWorkspaceManager.pruneToLimit(context); refresh++
                }, modifier = Modifier.fillMaxWidth())
                PrimaryActionButton(if (t.zh) "清理全部临时工作区" else "Clean all temp workspaces", { showCleanConfirm = true }, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    if (showCleanConfirm) {
        AlertDialog(
            onDismissRequest = { showCleanConfirm = false },
            title = { Text(if (t.zh) "清理全部临时工作区？" else "Clean all temp workspaces?") },
            text = { Text(if (t.zh) "将删除所有 MCP 工具产生的中间目录（smali 批处理、APK 解包/回编、jadx 输出等）。已保存到工作目录的文件不受影响。" else "All intermediate dirs (smali batch, APK decode/build, jadx output...) will be deleted. Files saved to the work directory are unaffected.") },
            confirmButton = {
                TextButton({
                    TempWorkspaceManager.cleanAll(context)
                    showCleanConfirm = false
                    refresh++
                }) { Text(if (t.zh) "清理" else "Clean", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ showCleanConfirm = false }) { Text(if (t.zh) "取消" else "Cancel") } },
        )
    }
}

// ─────────────────────────── 工作区管理 ───────────────────────────

@Composable
internal fun SettingsWorkspacePage(t: UiText, settings: SettingsStore, onOpenServiceConfig: () -> Unit, onOpenTemp: () -> Unit = {}) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var refresh by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_EXPRESSION") refresh
    val workDir = WorkspacePolicy.workDirPath(context)
    val tunnelOn = WorkspacePolicy.isTunnelActive(context)
    val roots = WorkspacePolicy.allowedRoots(context)
    val tempCount = TempWorkspaceManager.count(context)

    PageScroll {
        GlassGroup(title = if (t.zh) "工作目录" else "Work directory") {
            NavRow(
                title = workDir?.takeIf { it.isNotBlank() } ?: if (t.zh) "未设置" else "Not set",
                subtitle = if (workDir.isNullOrBlank())
                    if (t.zh) "尚未选择工作目录，MCP 工具暂无文件工作区" else "No work directory selected; MCP tools have no file workspace"
                else
                    if (t.zh) "MCP 工具默认文件访问范围，在「服务配置」中修改" else "Default file scope of MCP tools; change it in Service config",
                icon = Icons.Default.FolderOpen,
                onClick = {},
            )
            if (workDir.isNullOrBlank()) {
                GroupDivider()
                Row(Modifier.padding(14.dp)) {
                    PrimaryActionButton(
                        if (t.zh) "选择工作目录" else "Choose work directory",
                        onOpenServiceConfig,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                GroupDivider()
                Row(Modifier.padding(14.dp)) {
                    SecondaryActionButton(
                        if (t.zh) "复制路径" else "Copy path",
                        {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(workDir))
                            Toast.makeText(context, (if (t.zh) "已复制: " else "Copied: ") + workDir, Toast.LENGTH_LONG).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        GlassGroup(title = if (t.zh) "访问策略" else "Access policy") {
            NavRow(
                title = if (tunnelOn)
                    if (t.zh) "隧道已开启 · 全盘放行" else "Tunnel active · full access"
                else
                    if (t.zh) "隧道未开启 · 仅工作目录" else "No tunnel · work dir only",
                subtitle = if (t.zh)
                    "开启任一隧道后 MCP 工具可访问 /sdcard 与 /storage/emulated/0"
                else
                    "Starting any tunnel lets MCP tools access /sdcard and /storage/emulated/0",
                icon = Icons.Default.Cloud,
                onClick = {},
            )
            for (root in roots) {
                GroupDivider()
                NavRow(
                    title = root,
                    subtitle = if (t.zh) "允许访问" else "Allowed",
                    icon = Icons.Default.FolderOpen,
                    onClick = {},
                )
            }
        }

        GlassGroup(title = if (t.zh) "临时工作区" else "Temp workspaces") {
            NavRow(
                title = if (t.zh) "$tempCount 个临时工作区" else "$tempCount temp workspaces",
                subtitle = if (t.zh) "数量上限 ${settings.tempWorkspaceLimit}，点按进入管理（上限/清理）" else "Limit ${settings.tempWorkspaceLimit}; tap to manage (limit / clean)",
                icon = Icons.Default.Storage,
                onClick = onOpenTemp,
            )
        }
    }
}
