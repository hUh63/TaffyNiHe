package top.wkbin.taixu.runtime

enum class ProjectType {
    ANDROID, FLUTTER, REVERSE, GENERAL, TAIXU_PLUGIN;

    val displayName: String
        get() = when (this) {
            ANDROID -> "Android"
            FLUTTER -> "Flutter"
            REVERSE -> "APK 逆向"
            GENERAL -> "通用"
            TAIXU_PLUGIN -> "太墟本地插件"
        }
}
