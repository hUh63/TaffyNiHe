package com.soreverse.mcp

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateDpAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import com.soreverse.mcp.core.BackupCrypto
import com.soreverse.mcp.core.BackupHistoryEntry
import com.soreverse.mcp.core.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 备份与恢复页（UI 同步上游 SOMCP v1.0.21 设计）：
 * 开关联动（含密钥强制加密并锁定）+ 双动作卡 + 备份历史 + 结果反馈。
 * 加密/导入/导出逻辑沿用塔菲原有实现，行为不变。
 */
@Composable
internal fun SettingsBackupRestorePage(t: UiText, settings: SettingsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var includeSecrets by remember { mutableStateOf(false) }
    var encryptEnabled by remember { mutableStateOf(false) }
    var encryptPassword by remember { mutableStateOf("") }
    var encryptConfirm by remember { mutableStateOf("") }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var resultOk by remember { mutableStateOf(false) }

    // Warning dialog state
    var showEncryptWarning by remember { mutableStateOf(false) }

    // Decrypt dialog state (for import)
    var decryptDialogVisible by remember { mutableStateOf(false) }
    var decryptPassword by remember { mutableStateOf("") }
    var decryptError by remember { mutableStateOf<String?>(null) }
    var pendingEncryptedBytes by remember { mutableStateOf<ByteArray?>(null) }

    // ----- 备份历史（最近成功导出，最新在前）-----
    var history by remember { mutableStateOf(settings.backupHistory()) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val json = settings.toJsonString(maskSecrets = !includeSecrets)
                val bytes = if (encryptEnabled && encryptPassword.isNotBlank()) {
                    withContext(Dispatchers.IO) {
                        BackupCrypto.encrypt(json, encryptPassword)
                    }
                } else {
                    json.toByteArray(Charsets.UTF_8)
                }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(bytes)
                    } ?: error("Cannot open output file")
                }
                settings.recordBackup(System.currentTimeMillis(), bytes.size.toLong())
            }.onSuccess {
                history = settings.backupHistory()
                resultOk = true
                resultMessage = t.backupExportSuccess
            }.onFailure { error ->
                resultOk = false
                resultMessage = error.message ?: t.backupImportError
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        input.readBytes()
                    } ?: error("Cannot read input file")
                }
                if (BackupCrypto.isEncrypted(bytes)) {
                    // File is encrypted — show password dialog
                    pendingEncryptedBytes = bytes
                    decryptPassword = ""
                    decryptError = null
                    decryptDialogVisible = true
                } else {
                    // Plaintext JSON — import directly
                    val json = bytes.decodeToString()
                    check(settings.fromJsonString(json, allowSecrets = includeSecrets).optBoolean("ok", false))
                    resultOk = true
                    resultMessage = t.backupImportSuccess
                }
            }.onFailure { error ->
                resultOk = false
                resultMessage = "${t.backupImportError}: ${error.message.orEmpty()}"
            }
        }
    }

    // Decrypt dialog
    if (decryptDialogVisible) {
        AlertDialog(
            onDismissRequest = {
                decryptDialogVisible = false
                pendingEncryptedBytes = null
                decryptPassword = ""
                decryptError = null
            },
            title = { Text(t.backupDecryptPassword) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        t.backupDecryptPasswordHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = decryptPassword,
                        onValueChange = { decryptPassword = it; decryptError = null },
                        label = { Text(t.backupEncryptPassword) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    decryptError?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val bytes = pendingEncryptedBytes ?: return@launch
                            runCatching {
                                val json = withContext(Dispatchers.IO) {
                                    BackupCrypto.decrypt(bytes, decryptPassword)
                                }
                                check(settings.fromJsonString(json, allowSecrets = includeSecrets).optBoolean("ok", false))
                                decryptDialogVisible = false
                                pendingEncryptedBytes = null
                                decryptPassword = ""
                                resultOk = true
                                resultMessage = t.backupImportSuccess
                            }.onFailure { error ->
                                decryptError = error.message?.let {
                                    if (it.contains("password") || it.contains("tag mismatch") || it.contains("AEADBadTagException")) {
                                        t.backupDecryptFailed
                                    } else {
                                        "${t.backupImportError}: $it"
                                    }
                                } ?: t.backupDecryptFailed
                            }
                        }
                    },
                    enabled = decryptPassword.isNotBlank(),
                ) { Text(t.backupImport) }
            },
            dismissButton = {
                TextButton(onClick = {
                    decryptDialogVisible = false
                    pendingEncryptedBytes = null
                    decryptPassword = ""
                    decryptError = null
                }) { Text(if (t.zh) "取消" else "Cancel") }
            },
        )
    }

    // Encryption warning dialog
    if (showEncryptWarning) {
        AlertDialog(
            onDismissRequest = { showEncryptWarning = false },
            title = { Text(t.backupEncryptWarningTitle) },
            text = {
                Text(
                    t.backupEncryptWarning,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(onClick = { showEncryptWarning = false }) {
                    Text(if (t.zh) "我已知晓" else "I understand")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEncryptWarning = false }) { Text(if (t.zh) "取消" else "Cancel") }
            },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = LocalUiMetrics.current.pagePad, vertical = 8.dp)
            .padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(LocalUiMetrics.current.sectionGap),
    ) {
        GlassGroup(title = t.backupLocal) {
            // 上游 1.0.21 借鉴: 包含密钥时强制启用加密并锁定加密开关
            BackupToggleRow(
                text = t.backupIncludeSecrets,
                subtitle = t.backupIncludeSecretsSubtitle,
                checked = includeSecrets,
            ) { enabled ->
                includeSecrets = enabled
                if (enabled && !encryptEnabled) {
                    encryptEnabled = true
                    showEncryptWarning = true
                }
            }
            GroupDivider()
            BackupToggleRow(
                text = t.backupEncryptToggle,
                subtitle = t.backupEncryptToggleSubtitle,
                checked = encryptEnabled,
                // 包含密钥时锁定开关，强制保持密码加密开启
                enabled = !includeSecrets,
            ) { enabled ->
                if (enabled) {
                    showEncryptWarning = true
                } else if (!includeSecrets) {
                    encryptEnabled = false
                    encryptPassword = ""
                    encryptConfirm = ""
                }
            }
            if (encryptEnabled) {
                GroupDivider()
                OutlinedTextField(
                    value = encryptPassword,
                    onValueChange = { encryptPassword = it },
                    label = { Text(t.backupEncryptPassword) },
                    placeholder = { Text(t.backupEncryptPasswordHint) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                )
                OutlinedTextField(
                    value = encryptConfirm,
                    onValueChange = { encryptConfirm = it },
                    label = { Text(t.backupEncryptConfirm) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = encryptConfirm.isNotEmpty() && encryptPassword != encryptConfirm,
                    supportingText = if (encryptConfirm.isNotEmpty() && encryptPassword != encryptConfirm) {
                        { Text(t.backupPasswordMismatch, color = MaterialTheme.colorScheme.error) }
                    } else null,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                )
                Text(
                    t.backupEncryptWarning,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // 双动作卡（导出 / 导入）
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BackupActionCard(
                label = t.backupExport,
                icon = Icons.Default.Upload,
                onClick = {
                    when {
                        encryptEnabled && encryptPassword.isBlank() -> {
                            resultOk = false
                            resultMessage = t.backupPasswordRequired
                        }
                        encryptEnabled && encryptPassword != encryptConfirm -> {
                            resultOk = false
                            resultMessage = t.backupPasswordMismatch
                        }
                        includeSecrets && !encryptEnabled -> {
                            resultOk = false
                            resultMessage = if (t.zh) "备份包含敏感信息(令牌/密钥)，必须启用密码加密后才能导出" else "Secrets require encryption before export"
                        }
                        else -> exportLauncher.launch("somcp_settings_backup.json")
                    }
                },
                modifier = Modifier.weight(1f),
            )
            BackupActionCard(
                label = t.backupImport,
                icon = Icons.Default.Download,
                onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                modifier = Modifier.weight(1f),
            )
        }

        // 备份历史（上游 1.0.21 借鉴）
        GlassGroup(title = t.backupHistory) {
            if (history.isEmpty()) {
                Text(
                    t.backupHistoryEmpty,
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                history.forEachIndexed { index, entry ->
                    if (index > 0) GroupDivider()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                formatBackupTime(entry.timestamp),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                formatBackupSize(entry.sizeBytes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            t.backupRestoreAction,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { importLauncher.launch(arrayOf("application/json", "*/*")) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        resultMessage?.let { message ->
            GlassGroup {
                Text(
                    message,
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (resultOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** 标题 + 副标题 + 自定义动画开关（上游 v1.0.21 设计）。 */
@Composable
private fun BackupToggleRow(
    text: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        BackupSwitch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
        )
    }
}

/** 自定义开关：灰色轨道 / 开启 Primary 蓝，白色圆形拇指带位移动画（上游设计）。 */
@Composable
private fun BackupSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val trackWidth = 48.dp
    val trackHeight = 28.dp
    val thumbSize = 24.dp
    val thumbInset = 2.dp
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) trackWidth - thumbSize - thumbInset else thumbInset,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "backupSwitchThumb",
    )
    Box(
        modifier = modifier
            .size(width = trackWidth, height = trackHeight)
            .clip(RoundedCornerShape(trackHeight / 2))
            .background(
                if (checked) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
            )
            .alpha(if (enabled) 1f else 0.4f)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(thumbSize)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

/** 导出/导入动作卡：圆形图标底 + 标签（上游 v1.0.21 设计）。 */
@Composable
private fun BackupActionCard(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(LocalUiMetrics.current.cardRadius)
    Column(
        modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
            .border(
                androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
                shape,
            )
            .clickable(onClick = onClick)
            .padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private val backupTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun formatBackupTime(timestamp: Long): String = runCatching {
    Instant.ofEpochMilli(timestamp)
        .atZone(ZoneId.systemDefault())
        .format(backupTimeFormatter)
}.getOrDefault("")

private fun formatBackupSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
