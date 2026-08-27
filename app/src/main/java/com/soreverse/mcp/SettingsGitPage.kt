package com.soreverse.mcp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soreverse.mcp.core.LinuxRootfs
import com.soreverse.mcp.core.WorkspacePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置 → Git 仓库：完整 Git 集成（走内置 Linux rootfs，proot/chroot 双通道）。
 *
 * - 仓库目录默认塔菲工作区，宿主目录 bind 挂载到 rootfs /mnt/ws，git 全功能可用
 * - 一键安装 git（apk/apt，随发行版自动选择）
 * - 操作: init / status / add+commit / log / diff / remote 管理 / push / pull
 * - GIT_TERMINAL_PROMPT=0 防交互卡死；输出等宽展示可复制
 */
@Composable
internal fun SettingsGitPage(t: UiText) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val zh = t.zh
    val clipboard = LocalClipboardManager.current
    val bg = Color(0xFF0B0F14)
    val fg = Color(0xFFD6E2F0)
    val dim = Color(0xFF8E8E93)

    val distros = remember { LinuxRootfs.distros(context) }
    var distro by remember { mutableStateOf(distros.firstOrNull()?.name ?: "alpine") }
    var repoPath by remember {
        mutableStateOf(WorkspacePolicy.workDirPath(context) ?: (context.filesDir.resolve("workspace").absolutePath))
    }
    var remoteUrl by remember { mutableStateOf("") }
    var commitMsg by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var gitOk by remember { mutableStateOf<Boolean?>(null) }
    var repoSummary by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    val outScroll = rememberScrollState()

    fun appendOut(s: String) { output = output + s + "\n" }

    /** 在 rootfs 内执行 git 脚本；工作区 bind 到 /mnt/ws。返回 null 表示通道不可用。 */
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
            if (r == null) appendOut("[通道不可用: 请先在「Linux 环境」解压 $distro]")
            else onDone(r)
        }
    }

    /** 刷新 git 可用性 + 仓库摘要。 */
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
            if (lines.any { it.trim() == "NOREPO" }) repoSummary = if (zh) "非 git 仓库（可初始化）" else "Not a git repo"
            else {
                val br = lines.firstOrNull { it.startsWith("BRANCH") }?.removePrefix("BRANCH ")?.trim().orEmpty()
                val ch = lines.firstOrNull { it.startsWith("CHANGED") }?.removePrefix("CHANGED ")?.trim().orEmpty()
                val ll = lines.firstOrNull { it.startsWith("LASTLOG") }?.removePrefix("LASTLOG ")?.trim().orEmpty()
                val rm = lines.firstOrNull { it.startsWith("REMOTE") }?.removePrefix("REMOTE ")?.trim().orEmpty()
                if (br.isNotEmpty()) {
                    repoSummary = "[$br] 改动 $ch · $ll" + if (rm != "none") " · origin: $rm" else ""
                    remoteUrl = if (rm != "none") rm else remoteUrl
                } else repoSummary = ""
            }
        }
    }
    LaunchedEffect(distro, repoPath) { refreshStatus() }

    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // ── 标题 ──
        Text(
            if (zh) "Git 仓库 · 内置 Linux 完整集成" else "Git repo · full integration via built-in Linux",
            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            if (zh) "git 运行于内置 rootfs（proot/chroot 自动选择），仓库目录 bind 到 /mnt/ws，支持完整工作区版本管理"
            else "git runs inside the built-in rootfs (proot/chroot), repo dir bind-mounted at /mnt/ws",
            style = MaterialTheme.typography.bodySmall, color = dim,
        )

        // ── 发行版 + git 可用性 ──
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            distros.forEach { d ->
                FilterChip(
                    selected = distro == d.name,
                    onClick = { distro = d.name; output = "" },
                    label = { Text("${d.name} · ${d.pkgMgr}", fontSize = 11.sp) },
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when (gitOk) {
                    true -> if (zh) "● git 可用" else "● git ready"
                    false -> if (zh) "● git 未安装" else "● git missing"
                    null -> if (zh) "● 状态未知（刷新检测）" else "● unknown"
                },
                style = MaterialTheme.typography.bodySmall,
                color = when (gitOk) { true -> AppPalette.green; false -> AppPalette.red; null -> dim },
                modifier = Modifier.weight(1f).padding(top = 10.dp),
            )
            if (gitOk == false) {
                Button(
                    onClick = {
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
                    },
                    enabled = !running,
                    colors = ButtonDefaults.buttonColors(containerColor = AppPalette.green),
                ) { Text(if (zh) "一键安装 git" else "Install git", fontSize = 12.sp) }
            } else {
                OutlinedButton(onClick = { refreshStatus() }, enabled = !running) {
                    Text(if (zh) "刷新状态" else "Refresh", fontSize = 12.sp)
                }
            }
        }

        // ── 仓库路径 ──
        OutlinedTextField(
            value = repoPath,
            onValueChange = { repoPath = it },
            label = { Text(if (zh) "仓库目录（默认塔菲工作区）" else "Repo dir (default: taffy workspace)") },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = fg),
            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = bg, unfocusedContainerColor = bg, focusedTextColor = fg, unfocusedTextColor = fg, cursorColor = AppPalette.blue),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        if (repoSummary.isNotEmpty()) {
            Text(repoSummary, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp), color = AppPalette.blue)
        }

        // ── 基础操作 ──
        Text(if (zh) "基础操作" else "Basics", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = {
                gitRun("git init -b main && git config user.email 'taffy@local' && git config user.name 'taffy' && echo '[已初始化仓库 (分支 main)]'") { r ->
                    appendOut(r.output.trim()); refreshStatus()
                }
            }, enabled = !running) { Text("init", fontSize = 12.sp) }
            OutlinedButton(onClick = {
                gitRun("git status && echo --- && git status --porcelain | head -40") { r -> appendOut(r.output.trim()) }
            }, enabled = !running) { Text("status", fontSize = 12.sp) }
            OutlinedButton(onClick = {
                gitRun("git diff --stat | head -40 && echo '---' && git diff | head -200") { r -> appendOut(r.output.trim().ifEmpty { if (zh) "[无未暂存改动]" else "[no unstaged changes]" }) }
            }, enabled = !running) { Text("diff", fontSize = 12.sp) }
            OutlinedButton(onClick = {
                gitRun("git log --oneline --decorate -15") { r -> appendOut(r.output.trim().ifEmpty { if (zh) "[暂无提交]" else "[no commits]" }) }
            }, enabled = !running) { Text("log", fontSize = 12.sp) }
            OutlinedButton(onClick = {
                gitRun("git add -A && git status --short | head -40") { r -> appendOut(if (r.code == 0) "[已暂存全部改动]" else r.output.trim()) }
            }, enabled = !running) { Text("add -A", fontSize = 12.sp) }
        }

        // ── 提交 ──
        OutlinedTextField(
            value = commitMsg,
            onValueChange = { commitMsg = it },
            label = { Text(if (zh) "提交信息（commit message）" else "Commit message") },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, color = fg),
            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = bg, unfocusedContainerColor = bg, focusedTextColor = fg, unfocusedTextColor = fg, cursorColor = AppPalette.blue),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (commitMsg.isBlank()) { appendOut("[请先填写提交信息]"); return@Button }
                    val msg = commitMsg.trim().replace("\"", "\\\"")
                    gitRun("git add -A && git commit -m \"$msg\" && git log --oneline -1") { r ->
                        appendOut(r.output.trim())
                        if (r.code == 0) commitMsg = ""
                        refreshStatus()
                    }
                },
                enabled = !running,
                modifier = Modifier.weight(1f),
            ) { Text(if (zh) "暂存全部并提交" else "Stage all & commit") }
        }

        // ── 远程 ──
        Text(if (zh) "远程仓库" else "Remote", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = remoteUrl,
            onValueChange = { remoteUrl = it },
            label = { Text(if (zh) "远程地址（HTTPS，私有仓库需在 rootfs 内配置凭据）" else "Remote URL (HTTPS; configure creds in rootfs)") },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = fg),
            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = bg, unfocusedContainerColor = bg, focusedTextColor = fg, unfocusedTextColor = fg, cursorColor = AppPalette.blue),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = {
                val u = remoteUrl.trim()
                if (u.isEmpty()) { appendOut("[请填写远程地址]"); return@OutlinedButton }
                gitRun("git remote remove origin 2>/dev/null; git remote add origin \"$u\" && git remote -v") { r -> appendOut(r.output.trim()) }
            }, enabled = !running) { Text(if (zh) "设置 origin" else "Set origin", fontSize = 12.sp) }
            OutlinedButton(onClick = {
                gitRun("git fetch origin --prune 2>&1 && git status -sb") { r -> appendOut(r.output.trim()) }
            }, enabled = !running) { Text("fetch", fontSize = 12.sp) }
            OutlinedButton(onClick = {
                gitRun("git pull --no-rebase origin \$(git symbolic-ref --short HEAD 2>/dev/null || echo main) 2>&1") { r -> appendOut(r.output.trim()) }
            }, enabled = !running) { Text("pull", fontSize = 12.sp) }
            OutlinedButton(onClick = {
                val br = "main"
                gitRun("git push -u origin $br 2>&1") { r -> appendOut(r.output.trim()) }
            }, enabled = !running) { Text("push", fontSize = 12.sp) }
        }
        Text(
            if (zh) "提示: push 私有仓库需先在终端进入 rootfs 配置凭据（如 https://<token>@github.com/... 或 git credential store）"
            else "Tip: for private push, configure credentials in rootfs terminal (token URL or credential store)",
            style = MaterialTheme.typography.labelSmall, color = dim,
        )

        // ── 输出 ──
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxWidth().height(220.dp)
                .background(bg, RoundedCornerShape(12.dp)),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("git output", style = MaterialTheme.typography.labelSmall, color = dim, modifier = Modifier.weight(1f))
                    Text(
                        if (zh) "复制" else "Copy",
                        style = MaterialTheme.typography.labelSmall, color = AppPalette.blue,
                        modifier = Modifier.clickable { if (output.isNotBlank()) clipboard.setText(AnnotatedString(output)) }.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                androidx.compose.foundation.layout.Divider(color = Color(0xFF1E2630))
                SelectionContainer {
                    Column(Modifier.fillMaxSize().verticalScroll(outScroll).padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(
                            output.ifEmpty { if (zh) "命令输出显示在这里…" else "Output appears here…" },
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp),
                            color = if (output.isEmpty()) dim else fg,
                        )
                    }
                }
            }
        }
        LaunchedEffect(output) { runCatching { outScroll.scrollTo(outScroll.maxValue) } }
    }
}
