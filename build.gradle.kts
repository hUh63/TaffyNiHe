plugins {
    // 太墟移植：插件版本统一由 gradle/libs.versions.toml（版本目录）提供，
    // 避免与太墟模块的 convention 插件出现「同一插件两个版本」的冲突。
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// 太墟移植：annotation-experimental 1.5.1 修掉 @Serializable 生成代码的
// InternalSerializationApi 误报（上游同款约束，见 KTIJ-31549）。
val annotationExperimentalMinVersion = libs.versions.annotationExperimental.get()
subprojects {
    listOf("com.android.application", "com.android.library", "com.android.test").forEach { pluginId ->
        pluginManager.withPlugin(pluginId) {
            dependencies.constraints.add(
                "implementation",
                "androidx.annotation:annotation-experimental:$annotationExperimentalMinVersion",
            ) {
                because("1.5.1 fixes false InternalSerializationApi opt-in reports in Android Lint")
            }
        }
    }
}
