package top.wkbin.taixu

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import top.wkbin.taixu.core.datastore.AppearancePreferences
import top.wkbin.taixu.core.model.ThemePalette
import top.wkbin.taixu.ui.navigation.TaiXuNavHost
import top.wkbin.taixu.ui.theme.TaiXuTheme

/**
 * 太墟壳的「可嵌入根」。
 *
 * 上游 [MainActivity] 的 setContent 里除了主题与导航，还绑定了 splash、onboarding、
 * 前台服务拉起等 Activity 级行为。宿主（塔菲逆核）把太墟作为**唯一主界面**时，不能在
 * 自己的 Activity 里复刻那一整套，只需要「主题 + 导航」这两层，因此单独抽成这个入口：
 *
 * - 主题：跟随太墟设置里的 themeMode / dynamicColor / themePalette / appFontScale（与上游一致）；
 * - 导航：直接渲染 [TaiXuNavHost]，其中「逆向」中枢由宿主通过 HostWorkspaceRegistry 注入；
 * - 不接管 onboarding：宿主已有自己的权限与首次引导链路，模型 Key 可在太墟设置里补。
 *
 * 调用前必须保证 Koin 已启动（宿主用 `GlobalContext.get()` 判断并降级）。
 */
@Composable
fun TaiXuEmbeddedRoot() {
    // 与上游 MainActivity 一致：主题相关偏好走 AppearancePreferences 窄接口（含 themePalette）。
    val settingsDataStore: AppearancePreferences = koinInject()
    val themeMode by settingsDataStore.themeMode.collectAsStateWithLifecycle(initialValue = "system")
    val dynamicColorEnabled by settingsDataStore.dynamicColorEnabled.collectAsStateWithLifecycle(initialValue = true)
    val themePalette by settingsDataStore.themePalette.collectAsStateWithLifecycle(initialValue = ThemePalette())
    val pageScale by settingsDataStore.appFontScale.collectAsStateWithLifecycle(initialValue = 1f)
    val systemDensity = LocalDensity.current
    val isDark = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }

    CompositionLocalProvider(
        LocalDensity provides Density(
            density = systemDensity.density * pageScale.coerceIn(0.8f, 1.3f),
            fontScale = systemDensity.fontScale,
        ),
    ) {
        TaiXuTheme(
            darkTheme = isDark,
            dynamicColor = dynamicColorEnabled,
            palette = themePalette,
        ) {
            TaiXuNavHost(globalNavigationBus = null)
        }
    }
}
