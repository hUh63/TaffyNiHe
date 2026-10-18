package com.soreverse.mcp

import android.app.Application
import androidx.lifecycle.AndroidViewModel

/**
 * App 级长生命周期状态容器。
 *
 * 背景：MainActivity 未声明 configChanges，屏幕旋转、深色切换、字体缩放都会重建 Activity。
 * 而 analyzeState / workspaceState 原来挂在 `remember` 上——一次旋转就会把已经跑完的分析结果、
 * AI 对话、当前工作区与工具结果全部清空，用户得重新选文件、重跑一遍。
 *
 * 放进 ViewModel 后，配置变更期间这些对象原样保留（Activity 重建不会重新 new）。
 * 进程被系统杀掉的情况不在覆盖范围，那部分由各模块自己的磁盘持久化负责。
 */
internal class AppViewModel(application: Application) : AndroidViewModel(application) {

    val analyzeState = AnalyzeUiState()

    val workspaceState = WorkspaceState(application)

    val deepService = DeepAnalysisService(application)

    val updateManager = GitHubUpdateManager(application)
}
