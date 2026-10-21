// 太墟（wkbin/taixu）之应用层 UI/装配，原 :app 模块改造为库模块 :taixu。
// 目的：把太墟的全部页面与 Koin 装配原样装进塔菲逆核，宿主 app 通过 Activity 跳转进入。
// 与上游的差异（仅此三处）：
//   1) com.android.application → com.android.library（namespace 保持 top.wkbin.taixu，
//      资源前缀 taixu_ 与上游一致，R 类因此逐字不变）；
//   2) 移除 applicationId / 签名 / splits / APK 命名 / baselineprofile（宿主 app 负责）；
//   3) Manifest 的 application 属性与 LAUNCHER 入口交给宿主，避免双启动图标。
plugins {
    alias(libs.plugins.taixu.android.library.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "top.wkbin.taixu"
    // 与上游 app 一致：太墟资源统一以 taixu_ 前缀，避免与宿主 com.soreverse.mcp 资源撞名。
    resourcePrefix = "taixu_"

    defaultConfig {
        manifestPlaceholders["appLabel"] = "太墟"
        // 太墟的 release keep 规则随库模块传递给宿主 app（serialization/Shizuku/Shower/JGit 等），
        // 否则宿主 R8 会把反射加载的类裁掉或直接因缺失 JDK 类失败。
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    packaging {
        dex {
            useLegacyPackaging = true
        }
        jniLibs {
            // PRoot 在 Android 10+ 以「解压后的可执行文件」方式启动，PTY 后端同理。
            useLegacyPackaging = true
            // 太墟仅支持 arm64-v8a；AAR 里可能还带 legacy/x86 ABI，排除掉。
            excludes += listOf(
                "**/armeabi-v7a/*.so",
                "**/x86/*.so",
                "**/x86_64/*.so",
            )
            // PRoot tracee loader 是可执行载荷，不是 JNI 库，不能被 AGP strip。
            keepDebugSymbols += "**/libproot-loader.so"
        }
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/license.txt",
                "META-INF/notice.txt",
            )
        }
    }
}

dependencies {
    // 太墟上游 app 的全部依赖原样保留（Koin / Room / navigation3 / a2ui / ktor / jgit / media3 …）
    implementation(libs.androidx.profileinstaller)
    debugImplementation(libs.leakcanary.android)
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:network"))
    implementation(project(":core:security"))
    implementation(project(":core:datastore"))
    implementation(project(":runtime"))
    implementation(project(":project-template"))
    implementation(project(":tools"))
    implementation(project(":harness"))
    implementation(project(":feature:components"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:workspace"))
    implementation(project(":feature:workflow"))
    implementation(project(":feature:navigation"))
    implementation(project(":feature:custom_iteration"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:theme"))
    implementation(project(":feature:git"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.bundles.compose.ui)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.bundles.koin.compose)
    // 工作流定时计划：WorkManager 到点触发 + Koin Worker 注入
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.koin.workmanager)
    implementation(libs.bundles.room)
    implementation(libs.shizuku.provider)
    implementation(libs.okhttp)
    implementation(libs.ktor.client.core)
    // Android must use the AAR; the default JVM JAR does not package Android JNI libraries.
    implementation(libs.zstd) {
        artifact { type = "aar" }
    }
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bundles.coroutines)
    testImplementation(libs.bundles.test.robolectric)
    // Graph verification names the DataStore built by McpClientPreferences' Context constructor.
    testImplementation(libs.androidx.datastore.preferences)
    testImplementation(libs.koin.test)
    testImplementation(libs.androidx.work.testing)
    // Robolectric on Java 25 requires the same ASM override as core:database.
    testImplementation(libs.bundles.asm.test)
}
