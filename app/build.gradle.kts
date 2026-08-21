import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseKeystoreProperties = Properties().apply {
    val file = rootProject.file("release/keystore.properties")
    if (file.isFile) file.inputStream().use(::load)
}

android {
    // 逆核: namespace(源码包名/JNI 命名空间)保留 com.soreverse.mcp 不动 —— native JNI 函数名
    // 写死 Java_com_soreverse_mcp_...，改了要连 C++ 一起改极易崩且用户看不见。
    // applicationId(系统/商店识别的真实包名)改成我们自己的 com.taffynihe。
    namespace = "com.soreverse.mcp"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.taffynihe"
        minSdk = 26
        targetSdk = 36
        versionCode = 63
        versionName = "1.0.63"

        // 逆核: 禁用 CMake native 编译, 完全使用从原版 SOMCP 提取的预编译 so(在 jniLibs/)。
        // 原因: 我们没有 rizin/lief 的交叉编译产物, CMake 只会产出 stub 桩覆盖真 so。
        // externalNativeBuild {
        //     cmake {
        //         cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
        //         arguments += listOf("-DANDROID_STL=c++_shared")
        //     }
        // }
    }

    buildFeatures {
        aidl = true
        compose = true
        buildConfig = true
    }

    splits {
        abi {
            // 逆核: 只出 arm64-v8a。原版 native so 我们只提取了 arm64 版, 且目标用户(逆向)基本都是 arm64 机。
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }

    lint {
        checkReleaseBuilds = false
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(releaseKeystoreProperties.getProperty("storeFile", "release/so-reverse-mcp-release.jks"))
            storePassword = releaseKeystoreProperties.getProperty("storePassword", "")
            keyAlias = releaseKeystoreProperties.getProperty("keyAlias", "")
            keyPassword = releaseKeystoreProperties.getProperty("keyPassword", "")
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        debug {
            isJniDebuggable = true
            buildConfigField("String", "EXPECTED_SIGNER_SHA256", "\"\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            isJniDebuggable = false
            // 签名摘要已从 BuildConfig 明文迁移到 IntegrityGuard 的 XOR 混淆字节数组
            // (移除 dex 明文存储, 防止字符串直接提取; 见 IntegrityGuard.kt OBFUSCATED_SIGNER_DIGEST)
            buildConfigField("String", "EXPECTED_SIGNER_SHA256", "\"\"")
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    // 逆核: 禁用 CMake(改用 jniLibs 里原版预编译 so)。
    // externalNativeBuild {
    //     cmake {
    //         path = file("src/main/cpp/CMakeLists.txt")
    //         version = "3.22.1"
    //     }
    // }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "DebugProbesKt.bin",
                "cc.c",
                "r_styles.ini",
                "r_values.ini",
                "win32-x86/**",
                "win32-x86-64/**",
                "darwin/**",
                "natives/osx_*/**",
                "natives/windows_*/**",
                "com/sun/jna/aix-*/**",
                "com/sun/jna/darwin-*/**",
                "com/sun/jna/win32-*/**",
            )
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("io.ktor:ktor-server-core-jvm:3.5.1")
    implementation("io.ktor:ktor-server-cio-jvm:3.5.1")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("com.squareup.okhttp3:okhttp-sse:5.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.github.rikkahub:markdown:d79a97cc8e")
    implementation("org.jsoup:jsoup:1.22.2")

    // 权限管理: Shizuku (adb 级 shell 权限) + Dhizuku (设备所有者权限)
    // provider 模块提供 rikka.shizuku.ShizukuProvider（Manifest 必需，缺了启动闪退）
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("io.github.iamr0s:Dhizuku-API:2.5.3")

    implementation(files("libs/unidbg-api-0.9.9-android-patched.jar"))
    implementation(files("libs/unidbg-android-0.9.9-android-patched.jar"))
    implementation(files("libs/capstone-3.1.8-android-patched.jar"))
    implementation(files("libs/keystone-0.9.7-android-patched.jar"))
    implementation("net.java.dev.jna:jna:5.10.0@aar")
    implementation("commons-codec:commons-codec:1.21.0")
    implementation("org.apache.commons:commons-collections4:4.5.0")
    implementation("commons-io:commons-io:2.21.0")
    implementation("com.alibaba:fastjson:1.2.83")
    implementation("com.lambdapioneer.argon2kt:argon2kt:1.6.0")

    // 逆核: 内置逆向静态分析工具(纯 Java, 作为 MCP 工具聚合)。
    // jadx: dex→java 反编译
    implementation("io.github.skylot:jadx-core:1.5.1")
    implementation("io.github.skylot:jadx-dex-input:1.5.1")
    implementation("org.slf4j:slf4j-api:2.0.16")
    // APKEditor 依赖 ARSCLib: APK 资源解包/回编/合并拆分包(aapt 无关)
    implementation("io.github.reandroid:ARSCLib:1.3.5")
    // APKEditor: 完整 APK 反编译(资源→json/xml)/回编打包/合并拆分包(xapk/apks→单apk)/去混淆重构/加固保护。
    // 纯 Java, aapt 无关, 基于 ARSCLib。补齐 MT 管理器的"改完完整回编成 APK"最后一环。
    implementation("com.github.REAndroid:APKEditor:V1.4.9")
    // smali/baksmali: dex↔smali 汇编(Google 维护的 Android 友好 fork)
    implementation("com.android.tools.smali:smali:3.0.9")
    implementation("com.android.tools.smali:smali-baksmali:3.0.9")
    implementation("com.android.tools.smali:smali-dexlib2:3.0.9")
    // (dex2jar 已移除: 阿里云/central 缺子模块 dex-ir/d2j-external, 且 jadx 已直接 dex→java 更强, 边际价值低)
    // apksig: APK v1/v2/v3 签名(Google 官方, 纯 Java, apksigner 底层库), 用于回编打包后签名
    implementation("com.android.tools.build:apksig:8.7.3")
    // bouncycastle: 运行时生成自签名证书/密钥对(给回编后的 APK 签名用)
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    // DexKit: C++ 实现的高性能 dex 反混淆查找库(带 arm64 native so)。
    // 混淆 App 里靠特征(用了哪些字符串/调用/参数类型)反查被混淆的真实类名/方法名，逆向定位利器。
    implementation("org.luckypray:dexkit:2.0.4")
    implementation("com.github.zhkl0228:demumble:1.0.4")
    implementation("net.dongliu:apk-parser:2.6.10")
    implementation("com.github.zhkl0228:unidbg-unicorn2:0.9.9") {
        exclude(group = "com.github.zhkl0228", module = "unidbg-api")
        exclude(group = "com.github.zhkl0228", module = "capstone")
        exclude(group = "com.github.zhkl0228", module = "keystone")
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
