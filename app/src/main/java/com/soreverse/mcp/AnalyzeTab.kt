package com.soreverse.mcp

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.soreverse.mcp.core.DeepAnalysisService
import com.soreverse.mcp.core.DeepReportStore
import com.soreverse.mcp.core.EngineProvider
import com.soreverse.mcp.core.SettingsStore
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 深度分析对话全屏层（自 AnalyzeTab 提取）。
 * 工具页（AnalysisWorkspace）发起 AI 深度分析后，MainActivity 挂载本页进行对话；
 * 携带 AnalyzeUiState 的 deepTargetPath/deepMessages/deepAnalyzingPath 状态。
 */
@Composable
internal fun DeepAiChatScreen(
    t: UiText,
    settings: SettingsStore,
    state: AnalyzeUiState,
    scope: CoroutineScope,
    deepService: DeepAnalysisService,
    backProgress: Float,
    onLeaveDeepReport: () -> Unit,
) {
    val context = LocalContext.current
    val deepChatListState = rememberLazyListState()
    var showHistory by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxSize().graphicsLayer {
            translationX = size.width * backProgress
            alpha = 1f - 0.12f * backProgress
        },
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                title = state.deepTargetPath.substringAfterLast('/').ifBlank { if (t.zh) "AI 深度分析" else "AI Deep Analysis" },
                subtitle = if (state.deepAnalyzingPath != null) (if (t.zh) "正在生成" else "Generating") else settings.aiModel,
                showBack = true,
                onBack = onLeaveDeepReport,
                trailing = {
                    if (state.deepMessages.isNotEmpty()) {
                        IconButton(
                            onClick = { rewindDeepTurn(state) },
                            enabled = state.deepAnalyzingPath == null && state.deepMessages.any { it.role == DeepChatRole.USER },
                        ) {
                            Icon(Icons.Default.Restore, if (t.zh) "撤回上一轮" else "Rewind last turn")
                        }
                        IconButton(onClick = { showHistory = true }, enabled = state.deepMessages.any { it.role == DeepChatRole.USER }) {
                            Icon(Icons.Default.Restore, if (t.zh) "历史轮次" else "History")
                        }
                        if (showHistory) {
                            DeepHistoryDialog(state.deepMessages, t.zh, onRewindTo = { id -> rewindDeepTo(state, id); showHistory = false }, onDismiss = { showHistory = false })
                        }
                        IconButton(onClick = {
                            state.deepMessages.lastOrNull { it.role == DeepChatRole.ASSISTANT }?.text?.let { copy(context, it, t.copied) }
                        }) {
                            Icon(Icons.Default.ContentCopy, if (t.zh) "复制最新回复" else "Copy latest reply")
                        }
                    }
                },
            )
            LazyColumn(
                state = deepChatListState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = LocalUiMetrics.current.pagePad, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                items(state.deepMessages, key = { it.id }) { message ->
                    DeepChatMessageItem(message = message, zh = t.zh)
                }
                item(key = "deep-output-bottom") {
                    Spacer(Modifier.height(1.dp))
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.96f),
                tonalElevation = 1.dp,
                shadowElevation = 3.dp,
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = state.deepInput,
                        onValueChange = { state.deepInput = it },
                        modifier = Modifier.weight(1f).heightIn(min = 50.dp, max = 132.dp),
                        placeholder = { Text(if (t.zh) "继续提问" else "Ask a follow-up", maxLines = 1) },
                        shape = RoundedCornerShape(AppShape.lg),
                        maxLines = 5,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
                        ),
                    )
                    FilledIconButton(
                        onClick = {
                            if (state.deepAnalyzingPath != null) {
                                state.deepJob?.cancel(CancellationException("Stopped by user"))
                            } else {
                                val input = state.deepInput.trim()
                                if (input.isNotBlank() && state.deepTargetPath.isNotBlank()) {
                                    state.deepInput = ""
                                    launchDeepAnalysis(context, state.deepTargetPath, input, settings, state, scope, deepService, t.zh)
                                }
                            }
                        },
                        enabled = state.deepAnalyzingPath != null || state.deepInput.isNotBlank(),
                        modifier = Modifier.size(50.dp),
                        shape = CircleShape,
                        colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (state.deepAnalyzingPath != null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        ),
                    ) {
                        Icon(
                            if (state.deepAnalyzingPath != null) Icons.Default.Stop else Icons.Default.ArrowUpward,
                            if (state.deepAnalyzingPath != null) (if (t.zh) "停止" else "Stop") else (if (t.zh) "发送" else "Send"),
                        )
                    }
                }
            }
        }
    }
}
