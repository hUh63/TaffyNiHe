package top.wkbin.taixu

import top.wkbin.taixu.core.datastore.AppearancePreferences
import top.wkbin.taixu.host.TaixuShellGuard

/**
 * 壳层入口约定（宿主与壳之间的唯一契约）。
 *
 * 宿主在启动时依次判断：
 * 1. [TaixuShellGuard.shouldUseShell]：上次壳是否在首帧前崩过；
 * 2. [isTaiXuShellReady]：壳渲染所需的关键依赖能否真正解析（Koin 容器存在 ≠ 可用，
 *    v1.4.1 的 TranslationManager 崩溃就是反例）；
 * 两者都通过才 `startActivity(top.wkbin.taixu.MainActivity)`，否则宿主留在自己的界面。
 *
 * 壳运行在**自己的 Activity** 里（`installSplashScreen` + `enableEdgeToEdge` + 主题 +
 * onboarding 全链路原样），而不是嵌在宿主 Activity 里渲染——后者缺少 edge-to-edge 预处理，
 * 太墟按 insets 设计的布局会在 measure 阶段读到异常值（v1.4.2/1.4.3 的崩溃即此）。
 */
fun isTaiXuShellReady(): Boolean = runCatching {
    val koin = org.koin.core.context.GlobalContext.get()
    koin.get<AppearancePreferences>()
    koin.get<top.wkbin.taixu.core.common.translation.TranslationManager>()
    true
}.getOrElse { error ->
    android.util.Log.e("TaiXuShell", "shell not ready: ${error.message}", error)
    false
}
