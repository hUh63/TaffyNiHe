package com.soreverse.mcp

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.soreverse.mcp.core.FullBackup
import com.soreverse.mcp.core.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 备份与恢复页（UI 同步上游 SOMCP v1.0.21 设计）：
 * 开关联动（含密钥强制加密并锁定）+ 双动作卡 + 备份历史 + 结果反馈。
 * 加密/导入/导出逻辑沿用塔菲原有实现，行为不变。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsBackupRestorePage(t: UiText,
    onBack: () -> Unit, settings: SettingsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var includeSecrets by remember { mutableStateOf(false) }
    var encryptEnabled by remember { mutableStateOf(false) }
    var encryptPassword by rememberSaveable { mutableStateOf("") }
    var encryptConfirm by rememberSaveable { mutableStateOf("") }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var resultOk by remember { mutableStateOf(false) }

    // Warning dialog state
    var showEncryptWarning by remember { mutableStateOf(false) }

    // Decrypt dialog state (for import)
    var decryptDialogVisible by remember { mutableStateOf(false) }
    var decryptPassword by rememberSaveable { mutableStateOf("") }
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

    // ----- 全量备份（ZIP）：设置之外还覆盖任务 / 扩展 / 编辑快照 / 工作区 -----
    var fullBusy by remember { mutableStateOf(false) }
    var fullStep by remember { mutableStateOf<String?>(null) }
    var fullResult by remember { mutableStateOf<FullBackup.Summary?>(null) }
    var fullError by remember { mutableStateOf<String?>(null) }
    var fullPasswordDialog by remember { mutableStateOf(false) }
    var fullPassword by rememberSaveable { mutableStateOf("") }
    var restorePasswordDialog by remember { mutableStateOf(false) }
    var restorePassword by rememberSaveable { mutableStateOf("") }
    var pendingRestorePath by remember { mutableStateOf<String?>(null) }

    val fullBackupRunner: (String?) -> Unit = { pwd ->
        scope.launch {
            fullBusy = true
            fullError = null
            fullResult = null
            fullStep = null
            runCatching {
                FullBackup.backup(context, pwd?.takeIf { it.isNotBlank() }) { step ->
                    scope.launch(Dispatchers.Main) { fullStep = step }
                }
            }.onSuccess { summary ->
                fullResult = summary
                settings.recordBackup(System.currentTimeMillis(), summary.bytes)
                history = settings.backupHistory()
            }.onFailure { error ->
                fullError = error.message ?: error.javaClass.simpleName
            }
            fullStep = null
            fullBusy = false
        }
    }

    val fullRestoreRunner: (File, String?) -> Unit = { file, pwd ->
        scope.launch {
            fullBusy = true
            fullError = null
            fullResult = null
            fullStep = null
            runCatching {
                FullBackup.restore(context, file.absolutePath, pwd?.takeIf { it.isNotBlank() }) { step ->
                    scope.launch(Dispatchers.Main) { fullStep = step }
                }
            }.onSuccess { summary ->
                fullResult = summary
            }.onFailure { error ->
                fullError = error.message ?: error.javaClass.simpleName
            }
            fullStep = null
            fullBusy = false
            withContext(Dispatchers.IO) {
                runCatching { file.delete() }
            }
        }
    }

    // 选 ZIP：沿用页面既有 ActivityResult 写法，先落到 cacheDir 再交给 FullBackup
    val fullRestorePick = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val local = withContext(Dispatchers.IO) {
                    val f = File(context.cacheDir, "picked-full-backup-${System.currentTimeMillis()}.zip")
                    val input = context.contentResolver.openInputStream(uri) ?: error("Cannot read input file")
                    input.use { source -> f.outputStream().use { sink -> source.copyTo(sink) } }
                    f
                }
                pendingRestorePath = local.absolutePath
                restorePassword = ""
                restorePasswordDialog = true
            }.onFailure { error ->
                fullError = error.message ?: error.javaClass.simpleName
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
                        shape = RoundedCornerShape(AppShape.md),
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

    // 全量备份密码（可留空 = 明文 .zip）
    if (fullPasswordDialog) {
        AlertDialog(
            onDismissRequest = { fullPasswordDialog = false },
            title = { Text(if (t.zh) "全量备份（ZIP）" else "Full backup (ZIP)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (t.zh) {
                            "可选密码：留空输出明文 .zip；填写则用 Argon2id + AES-256-GCM 加密为 .bak.enc。敏感键（token/secret/password/key 等）一律脱敏。"
                        } else {
                            "Optional password: blank writes a plain .zip; otherwise Argon2id + AES-256-GCM (.bak.enc). Secret keys are always masked."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = fullPassword,
                        onValueChange = { fullPassword = it },
                        label = { Text(if (t.zh) "密码（可留空）" else "Password (optional)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        shape = RoundedCornerShape(AppShape.md),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        fullPasswordDialog = false
                        fullBackupRunner(fullPassword)
                    },
                ) { Text(if (t.zh) "开始备份" else "Start") }
            },
            dismissButton = {
                TextButton(onClick = { fullPasswordDialog = false }) { Text(if (t.zh) "取消" else "Cancel") }
            },
        )
    }

    // 从 ZIP 恢复（明文留空密码；加密包必须填密码）
    if (restorePasswordDialog) {
        AlertDialog(
            onDismissRequest = {
                restorePasswordDialog = false
                pendingRestorePath = null
            },
            title = { Text(if (t.zh) "从 ZIP 恢复" else "Restore from ZIP") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (t.zh) {
                            "明文 .zip 可留空密码；加密 .bak.enc 必须填密码。恢复前会自动生成 pre-restore 快照，失败时回滚。"
                        } else {
                            "Leave blank for a plain .zip; encrypted .bak.enc needs the password. A pre-restore snapshot is taken and used to roll back on failure."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = restorePassword,
                        onValueChange = { restorePassword = it },
                        label = { Text(if (t.zh) "密码（明文包可留空）" else "Password (blank for plain)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        shape = RoundedCornerShape(AppShape.md),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = pendingRestorePath != null,
                    onClick = {
                        val path = pendingRestorePath
                        restorePasswordDialog = false
                        pendingRestorePath = null
                        if (path != null) fullRestoreRunner(File(path), restorePassword)
                    },
                ) { Text(if (t.zh) "开始恢复" else "Restore") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        restorePasswordDialog = false
                        pendingRestorePath = null
                    },
                ) { Text(if (t.zh) "取消" else "Cancel") }
            },
        )
    }

    SettingsScaffold(
        title = if (t.zh) "备份与恢复" else "Backup & restore",
        onBack = onBack,
    ) {
        item("content") {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
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
                    shape = RoundedCornerShape(AppShape.md),
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
                    shape = RoundedCornerShape(AppShape.md),
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
                        TextButton(
                            onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                        ) {
                            Text(
                                t.backupRestoreAction,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
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

        // 全量备份（ZIP）：设置之外的本地快照（任务 / 扩展 / 编辑快照 / 工作区）
        GlassGroup(title = if (t.zh) "全量备份（ZIP）" else "Full backup (ZIP)") {
            ListItem(
                headlineContent = {
                    Text(
                        if (t.zh) "全量备份" else "Full backup",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                },
                supportingContent = {
                    Text(
                        if (t.zh) {
                            "打包偏好设置、任务、扩展、编辑快照与工作区；敏感键脱敏，单文件超过 20 MB 跳过"
                        } else {
                            "Packs prefs, tasks, extensions, snapshots and workspaces; secrets masked, files over 20 MB skipped"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                trailingContent = {
                    TextButton(
                        enabled = !fullBusy,
                        onClick = {
                            fullPassword = ""
                            fullPasswordDialog = true
                        },
                    ) { Text(if (t.zh) "备份" else "Back up") }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            GroupDivider()
            ListItem(
                headlineContent = {
                    Text(
                        if (t.zh) "从 ZIP 恢复" else "Restore from ZIP",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                },
                supportingContent = {
                    Text(
                        if (t.zh) {
                            "恢复顺序：偏好设置 → 任务/扩展/快照 → 工作区；恢复前自动生成快照，失败回滚"
                        } else {
                            "Order: prefs, then tasks/extensions/snapshots, then workspaces; auto snapshot and rollback"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                trailingContent = {
                    TextButton(
                        enabled = !fullBusy,
                        onClick = {
                            fullRestorePick.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                        },
                    ) { Text(if (t.zh) "恢复" else "Restore") }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            if (fullBusy || fullStep != null) {
                GroupDivider()
                InlineHint(text = fullStep ?: (if (t.zh) "处理中…" else "Working…"))
            }
            fullError?.let { message ->
                GroupDivider()
                InlineHint(
                    text = if (t.zh) "失败：$message" else "Failed: $message",
                    tone = HintTone.Error,
                )
            }
            fullResult?.let { summary ->
                GroupDivider()
                Text(
                    buildString {
                        append(if (t.zh) "路径：" else "Path: ").append(summary.path).append('\n')
                        append(if (t.zh) "大小：" else "Size: ").append(formatBackupSize(summary.bytes)).append('\n')
                        append(if (t.zh) "条目：" else "Entries: ").append(summary.entries)
                        append(if (t.zh) " ｜ 加密：" else " | encrypted: ").append(
                            if (summary.encrypted) (if (t.zh) "是" else "yes") else (if (t.zh) "否" else "no"),
                        )
                        summary.categories.forEach { category ->
                            append('\n').append(category.name).append(": ").append(category.entries)
                            if (category.skipped > 0) {
                                append(" (")
                                append(if (t.zh) "跳过 " else "skipped ")
                                append(category.skipped).append(")")
                            }
                        }
                        summary.preRestorePath?.let {
                            append('\n').append(if (t.zh) "恢复前快照：" else "Pre-restore: ").append(it)
                        }
                        if (summary.skipped.isNotEmpty()) {
                            append('\n').append(if (t.zh) "跳过项：" else "Skipped: ")
                            summary.skipped.take(4).forEach { item ->
                                append("\n· ").append(item)
                            }
                            if (summary.skipped.size > 4) append("\n· …")
                        }
                    },
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    
            }
        }
    }
}

/** 标题 + 副标题 + M3 Switch 开关行（上游 v1.0.21 设计）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupToggleRow(
    text: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                text,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
        },
        supportingContent = {
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        },
        trailingContent = {
            BackupSwitch(
                checked = checked,
                onCheckedChange = onChange,
                enabled = enabled,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** M3 官方 Switch（原自绘轨道/滑块已替换，颜色走 M3 默认）。 */
@Composable
private fun BackupSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
    )
}

/** 导出/导入动作卡（M3 Card）：圆形图标底 + 标签（上游 v1.0.21 设计）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupActionCard(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(LocalUiMetrics.current.cardRadius)
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
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
