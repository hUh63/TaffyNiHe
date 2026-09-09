package com.soreverse.mcp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsBlutterPage(t: UiText) {
    PageScroll {
        GlassGroup {
            Column(Modifier.padding(14.dp)) {
                Text(if (t.zh) "内置 Runner" else "Embedded runners", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                Text("Dart 3.11.5 / 3.12.2 / 3.13.0 / 3.13.1 · arm64-v8a", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (t.zh) "APK 内置 4 套完整 Blutter 分析 Runner（随包安装即用），覆盖 Flutter 3.41.x ~ 3.47.x 的 engine 快照。分析完全在手机本地完成，不需要 Python、网络、ADB 或远端服务。"
                    else "The APK embeds 4 full Blutter analysis runners covering Flutter 3.41.x ~ 3.47.x engine snapshots. Analysis runs fully on-device without Python, network, ADB, or remote services.",
                    modifier = Modifier.padding(top = 10.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            GroupDivider()
            Column(Modifier.padding(14.dp)) {
                Text(if (t.zh) "自动匹配" else "Auto matching", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                Text(
                    if (t.zh) "打开 Flutter SO 时，塔菲按 snapshot hash + ABI + 压缩指针模式在内置清单（runners.json）里自动匹配 Runner，命中即离线输出全量类/方法/反编译结构。Dart 3.13 起快照合并为单段（vm/isolate 合一），对应 Runner 已内置。"
                    else "When opening a Flutter SO, Taffy matches an embedded runner automatically by snapshot hash + ABI + compressed-pointer mode (runners.json). Dart 3.13 single-snapshot format is supported as well.",
                    modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            GroupDivider()
            Column(Modifier.padding(14.dp)) {
                Text(if (t.zh) "兼容性规则" else "Compatibility", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                Text(
                    if (t.zh) "未命中内置 Runner 的 Flutter/Dart 版本会明确返回不支持，不会尝试错误解析；后续版本随应用更新扩充 Runner，无需任何手动配置。"
                    else "Unmatched Flutter/Dart versions return an explicit unsupported-version error instead of a wrong parse. Future runners ship with app updates, no manual setup.",
                    modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
