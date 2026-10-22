package top.wkbin.taixu.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import top.wkbin.taixu.ui.components.RuntimeCard
import top.wkbin.taixu.ui.components.RuntimeIconName
import top.wkbin.taixu.ui.components.RuntimeIllustration
import top.wkbin.taixu.ui.components.RuntimeIllustrationScene

/**
 * 系统自检：把「移植进宿主后能否真的跑起来」变成一张可读清单。
 *
 * 逐项检测 ABI / Koin / Room / PRoot / PTY / 逆向引擎 / 发行版；点击任意一行可重新检测。
 * 失败项按是否致命分级：致命项（Koin、Room）会让整块功能不可用，受限项只让单个能力退级。
 */
@Composable
internal fun SelfCheckScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val zh = LocalConfiguration.current.locales[0]?.language?.startsWith("zh") == true
    fun s(zhText: String, enText: String) = if (zh) zhText else enText

    var tick by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf(TaixuDiagnostics.check(context)) }
    LaunchedEffect(tick) { items = TaixuDiagnostics.check(context) }

    val failed = items.count { !it.ok }
    val fatal = items.count { !it.ok && it.fatal }

    PersonalizationScaffold(s("系统自检", "Self check"), onBack) {
        item("self_check_illustration") {
            RuntimeIllustration(RuntimeIllustrationScene.Settings, Modifier.height(96.dp))
        }
        item("self_check_summary") {
            RuntimeCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = if (failed == 0) {
                            s("${items.size} 项全部正常", "All ${items.size} checks passed")
                        } else {
                            s("$failed 项需要处理", "$failed check(s) need attention")
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (fatal > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = s(
                            "点击任意一项可重新检测。致命项会让相关功能整块不可用，受限项只会让个别能力退级。",
                            "Tap any row to re-check. Fatal items disable a whole feature; limited items only degrade one capability.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item("self_check_section") {
            PersonalizationSection(s("检测项", "Checks")) {}
        }
        items.forEachIndexed { index, item ->
            item("self_check_${item.key}") {
                SettingsCategoryCard(
                    icon = if (item.ok) RuntimeIconName.Check else RuntimeIconName.Alert,
                    title = item.title,
                    subtitle = item.detail,
                    value = when {
                        item.ok -> s("正常", "OK")
                        item.fatal -> s("致命", "Fatal")
                        else -> s("受限", "Limited")
                    },
                    first = index == 0,
                    last = index == items.lastIndex,
                    onClick = { tick++ },
                )
            }
        }
    }
}
