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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.soreverse.mcp.core.SettingsStore
import com.soreverse.mcp.core.SigningKeyStore
import com.soreverse.mcp.core.TempWorkspaceManager
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
            var v1Name by remember { mutableStateOf(settings.apkV1SignerName) }
            Column(Modifier.padding(14.dp)) {
                OutlinedTextField(
                    value = v1Name,
                    onValueChange = { v1Name = it; settings.apkV1SignerName = it },
                    label = { Text(if (t.zh) "自定义 V1 签名数据文件名" else "Custom V1 signer name") },
                    supportingText = { Text(if (t.zh) "META-INF 下文件名（不含扩展名，默认 CERT）" else "File name under META-INF (no extension, default CERT)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            GroupDivider()
            ToggleRow(
                if (t.zh) "不签名时保留 V2/V3 签名数据" else "Keep V2/V3 signature when unsigned",
                settings.apkKeepV2V3WhenNoSign,
            ) { settings.apkKeepV2V3WhenNoSign = it }
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
    var limitText by remember { mutableStateOf(settings.tempWorkspaceLimit.toString()) }
    var showCleanConfirm by remember { mutableStateOf(false) }
    @Suppress("UNUSED_EXPRESSION") refresh
    val count = TempWorkspaceManager.count(context)
    val stats = TempWorkspaceManager.stats(context)

    PageScroll {
        GlassGroup(title = if (t.zh) "临时工作区数量" else "Temp workspace limit") {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = limitText,
                    onValueChange = { limitText = it.filter(Char::isDigit).take(3) },
                    label = { Text(if (t.zh) "数量上限 (1..100)" else "Limit (1..100)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                )
                PrimaryActionButton(if (t.zh) "应用" else "Apply", {
                    val next = limitText.toIntOrNull()?.coerceIn(1, 100) ?: settings.tempWorkspaceLimit
                    settings.tempWorkspaceLimit = next
                    limitText = next.toString()
                    TempWorkspaceManager.pruneToLimit(context)
                    refresh++
                })
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
