package com.soreverse.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.soreverse.mcp.core.LinuxRootfs
import com.soreverse.mcp.core.WorkspacePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置 → Git 仓库：完整 Git 集成（走内置 Linux rootfs，proot/chroot 双通道）。
 *
 * 修复：旧实现 LaunchedEffect(distro, repoPath) 以 repoPath 为 key，目录框每输入一个字符就
 * 触发一次 rootfs git 进程；现改为显式“应用”+ refreshTick。push 也不再硬编码 main 分支。
 * 图形化要点：分区卡片、统一终端框、破坏性操作二次确认、耗时操作覆盖式进度。
 */
@Composable
internal fun SettingsGitPage(t: UiText) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val zh = t.zh

    val distros = remember { LinuxRootfs.distros(context) }
    var distro by remember { mutableStateOf(distros.firstOrNull()?.name ?: "alpine") }
    var repoPath by remember {
        mutableStateOf(WorkspacePolicy.workDirPath(context) ?: (context.filesDir.resolve("workspace").absolutePath))
    }
    var repoInput by remember { mutableStateOf(repoPath) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var remoteUrl by remember { mutableStateOf("") }
    var commitMsg by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var runMsg by remember { mutableStateOf("") }
    var gitOk by remember { mutableStateOf<Boolean?>(null) }
    var repoSummary by remember { mutableStateOf("") }
    var pendingConfirm by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingMsg by remember { mutableStateOf("") }

    fun appendOut(s: String) {
        output = output + s + "\n"
    }

    fun gitRun(script: String, timeoutSec: Long = 90, onDone: (LinuxRootfs.ExecResult) -> Unit) {
        running = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                val ws = repoPath.trim()
                val binds = if (ws.isNotBlank()) listOf(ws to "/mnt/ws") else emptyList()
                LinuxRootfs.exec(
                    context, distro,
                    """
                    export HOME=/root
                    export GIT_TERMINAL_PROMPT=0
                    export PATH=/usr/bin:/bin:/usr/sbin:/sbin:${'$'}PATH
                    cd /mnt/ws 2>/dev/null || { echo '[仓库目录不可访问: 已 bind 到 /mnt/ws]'; exit 9; }
                    $script
                    """.trimIndent(),
                    timeoutSec, binds,
                )
            }
            running = false
            if (r == null) appendOut("[通道不可用: 请先在「Linux 环境」解压 $distro]") else onDone(r)
        }
    }

    fun refreshStatus() {
        gitRun(
            """
            if command -v git >/dev/null 2>&1; then
                echo "GIT_OK $(git --version 2>/dev/null)"
            else
                echo "GIT_MISSING"
            fi
            if [ -d .git ] || git rev-parse --git-dir >/dev/null 2>&1; then
                echo "BRANCH $(git symbolic-ref --short HEAD 2>/dev/null || echo detached)"
                echo "CHANGED $(git status --porcelain 2>/dev/null | wc -l | tr -d ' ')"
                echo "LASTLOG $(git log --oneline -1 2>/dev/null || echo 'no commits')"
                echo "REMOTE $(git remote get-url origin 2>/dev/null || echo 'none')"
            else
                echo "NOREPO"
            fi
            """.trimIndent(),
        ) { r ->
            val lines = r.output.lines()
            gitOk = when {
                lines.any { it.startsWith("GIT_OK") } -> true
                lines.any { it.startsWith("GIT_MISSING") } -> false
                else -> null
            }
            if (lines.any { it.trim() == "NOREPO" }) {
                repoSummary = if (zh) "非 git 仓库（可初始化）" else "Not a git repo"
            } else {
                val br = lines.firstOrNull { it.startsWith("BRANCH") }?.removePrefix("BRANCH ")?.trim().orEmpty()
                val ch = lines.firstOrNull { it.startsWith("CHANGED") }?.removePrefix("CHANGED ")?.trim().orEmpty()
                val ll = lines.firstOrNull { it.startsWith("LASTLOG") }?.removePrefix("LASTLOG ")?.trim().orEmpty()
                val rm = lines.firstOrNull { it.startsWith("REMOTE") }?.removePrefix("REMOTE ")?.trim().orEmpty()
                if (br.isNotEmpty()) {
                    repoSummary = "[$br] " + (if (zh) "改动 " else "changed ") + ch + " · " + ll + if (rm != "none") " · origin: $rm" else ""
                    remoteUrl = if (rm != "none") rm else remoteUrl
                } else {
                    repoSummary = ""
                }
            }
        }
    }

    LaunchedEffect(distro, refreshTick) { refreshStatus() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ── 环境 + 仓库 ──
        GlassGroup(title = if (zh) "内核环境" else "Rootfs") {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                distros.forEach { d ->
                    FilterChip(
                        selected = distro == d.name,
                        onClick = { distro = d.name; output = "" },
                        label = { Text("${d.name} · ${d.pkgMgr}") },
                    )
                }
            }
            DataRow(
                title = when (gitOk) {
                    true -> if (zh) "git 可用" else "git ready"
                    false -> if (zh) "git 未安装" else "git missing"
                    null -> if (zh) "状态未知" else "unknown"
                },
                subtitle = repoSummary.ifBlank { if (zh) "点此刷新状态" else "tap to refresh" },
                trailingText = if (gitOk == false) (if (zh) "安装 git" else "Install git") else (if (zh) "刷新" else "Refresh"),
                onClick = {
                    if (gitOk == false) {
                        gitRun(
                            when (distro) {
                                "ubuntu" -> "apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq git && git --version"
                                else -> "apk add --no-cache git && git --version"
                            },
                            timeoutSec = 300,
                        ) { r ->
                            appendOut(if (r.code == 0) "[git 安装成功]" else "[git 安装失败]\n${r.output.takeLast(600)}")
                            refreshStatus()
                        }
                    } else {
                        refreshStatus()
                    }
                },
            )
        }

        GlassGroup(title = if (zh) "仓库目录" else "Repository") {
            OutlinedTextField(
                value = repoInput,
                onValueChange = { repoInput = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                placeholder = { Text(if (zh) "仓库目录（默认塔菲工作区）" else "Repo dir (default: taffy workspace)", style = MaterialTheme.typography.bodySmall) },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { repoPath = repoInput; refreshTick++ }),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryActionButton(
                    if (zh) "应用目录并刷新" else "Apply & refresh",
                    { repoPath = repoInput; refreshTick++ },
                    Modifier.weight(1f),
                )
            }
        }

        // ── 基础操作 ──
        GlassGroup(title = if (zh) "基础操作" else "Basics") {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ActionChip("init", !running) {
                    pendingMsg = if (zh) "将在该目录初始化 Git 仓库（分支 main），不会删除已有文件。" else "Initialize a git repo (branch main) here."
                    pendingConfirm = {
                        gitRun("git init -b main && git config user.email 'taffy@local' && git config user.name 'taffy' && echo '[已初始化仓库 (分支 main)]'") { r ->
                            appendOut(r.output.trim()); refreshStatus()
                        }
                    }
                }
                ActionChip(if (zh) "状态" else "status", !running) {
                    gitRun("git status -sb") { r -> appendOut(r.output.trim()) }
                }
                ActionChip("diff", !running) {
                    gitRun("git --no-pager diff --stat") { r -> appendOut(r.output.trim().ifEmpty { "(无差异)" }) }
                }
                ActionChip("log", !running) {
                    gitRun("git --no-pager log --oneline -20") { r -> appendOut(r.output.trim()) }
                }
                ActionChip(if (zh) "暂存全部" else "add -A", !running) {
                    gitRun("git add -A && echo '[已暂存全部改动]'") { r -> appendOut(r.output.trim()); refreshStatus() }
                }
            }
        }

        // ── 提交 ──
        GlassGroup(title = if (zh) "提交" else "Commit") {
            OutlinedTextField(
                value = commitMsg,
                onValueChange = { commitMsg = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                placeholder = { Text(if (zh) "提交说明" else "Commit message", style = MaterialTheme.typography.bodySmall) },
                textStyle = MaterialTheme.typography.bodySmall,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (commitMsg.isNotBlank()) { /* 交由按钮 */ } }),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryActionButton(
                    if (zh) "暂存并提交" else "Add & commit",
                    {
                        val m = commitMsg.trim()
                        if (m.isNotEmpty()) {
                            pendingMsg = if (zh) "将暂存全部改动并提交：$m" else "Stage all and commit: $m"
                            pendingConfirm = {
                                gitRun("git add -A && git commit -m \"${m.replace("\"", "\\\"")}\"") { r ->
                                    appendOut(r.output.trim()); commitMsg = ""; refreshStatus()
                                }
                            }
                        }
                    },
                    Modifier.weight(1f),
                )
            }
        }

        // ── 远程 ──
        GlassGroup(title = if (zh) "远程" else "Remote") {
            OutlinedTextField(
                value = remoteUrl,
                onValueChange = { remoteUrl = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                placeholder = { Text("https://github.com/user/repo.git", style = MaterialTheme.typography.bodySmall) },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                singleLine = true,
            )
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ActionChip(if (zh) "设置 origin" else "set origin", !running) {
                    val u = remoteUrl.trim()
                    if (u.isNotEmpty()) {
                        pendingMsg = if (zh) "将覆盖 origin 为：$u（已存在则先移除）" else "Set origin to $u (removes existing)"
                        pendingConfirm = {
                            gitRun("git remote remove origin 2>/dev/null; git remote add origin \"$u\" && git remote -v") { r ->
                                appendOut(r.output.trim()); refreshStatus()
                            }
                        }
                    }
                }
                ActionChip("fetch", !running) {
                    gitRun("git fetch --prune") { r -> appendOut(r.output.trim()); refreshStatus() }
                }
                ActionChip(if (zh) "拉取" else "pull", !running) {
                    pendingMsg = if (zh) "将执行 git pull（合并远程到当前分支）。" else "Run git pull."
                    pendingConfirm = {
                        gitRun("git pull") { r -> appendOut(r.output.trim()); refreshStatus() }
                    }
                }
                ActionChip(if (zh) "推送" else "push", !running) {
                    pendingMsg = if (zh) "将推送【当前分支】到 origin。" else "Push the CURRENT branch to origin."
                    pendingConfirm = {
                        gitRun("br=\$(git symbolic-ref --short HEAD 2>/dev/null || echo main); git push origin \"\$br\"") { r ->
                            appendOut(r.output.trim()); refreshStatus()
                        }
                    }
                }
            }
        }

        // ── 输出 ──
        TerminalPane(
            text = output,
            title = if (zh) "输出" else "Output",
            onClear = { output = "" },
            placeholder = if (zh) "操作结果将显示在这里" else "Result appears here",
            maxHeight = 460.dp,
        )
    }

    pendingConfirm?.let { run ->
        ConfirmDialog(
            title = if (zh) "确认操作？" else "Confirm?",
            message = pendingMsg,
            confirmText = if (zh) "确认" else "Confirm",
            destructive = true,
            onConfirm = run,
            onDismiss = { pendingConfirm = null },
        )
    }

    BusyOverlay(visible = running, message = if (zh) "执行 git…" else "Running git…")
}

@Composable
private fun ActionChip(label: String, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(selected = false, onClick = onClick, enabled = enabled, label = { Text(label, fontFamily = FontFamily.Monospace) })
}
