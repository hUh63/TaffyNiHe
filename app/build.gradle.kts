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
    // Android 引入 minor SDK 版本后，compose 1.12.x（compose-bom 2026.08/09）要求 compileSdk >= 37：
    // 平台在 stable 渠道以 platforms;android-37.1 / 37.2 形式提供，故显式指定 minor。
    compileSdk = 37
    compileSdkMinor = 2
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.taffynihe"
        minSdk = 26
        targetSdk = 36
        versionCode = 171
        versionName = "1.3.60"

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
            // 逆核: 默认只出 arm64-v8a(与既有发布一致)。
            // 多 ABI 打包由 build-multiabi workflow 传入
            //   -PabiFilter=arm64-v8a,armeabi-v7a,x86,x86_64
            // 覆盖; 未指定时行为不变, 不影响既有单 ABI 发布流程。
            isEnable = true
            reset()
            val abiFilter = (project.findProperty("abiFilter") as String?)
                ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: listOf("arm64-v8a")
            include(*abiFilter.toTypedArray())
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
                // Eclipse ELK / EMF jar 的 OSGi 关于/插件元数据（根级裸文件）：多个 jar 重复，
                // Android 上无用；不排除会导致 mergeReleaseJavaResource 因资源重名失败。
                "about.html",
                "about.ini",
                "about.mappings",
                "about.properties",
                "modeling32.png",
                "plugin.properties",
                "plugin.xml",
                ".api_description",
                "feature.properties",
                // OSGi jar 签名/辅助元数据（ELK/EMF 多 jar 重复），Android 上无用
                "META-INF/ECLIPSE_.RSA",
                "META-INF/ECLIPSE_.SF",
                // BouncyCastle 1.86 起 bcpkix/bcutil/bcprov 三个 jar 各自带 JAR 签名，
                // 且都位于同一路径 —— 签名文件对 APK 无意义，直接排除。
                "META-INF/BCRSA204.RSA",
                "META-INF/BCRSA204.SF",
                // fastjson2 与 fastjson2-extension 都带 multi-release 的 module-info（Android 用不到），
                // 两个 jar 同路径重名会撞 mergeReleaseJavaResource，直接排除。
                "META-INF/versions/9/module-info.class",
            )
            // 这些必须保留一份但不能重复
            pickFirsts += setOf(
                "META-INF/MANIFEST.MF",
                "META-INF/eclipse.inf",
                // smali×3 + apksig 都带裸 LICENSE；BouncyCastle 1.86 三个 jar 都带 LICENSE.md；
                // 保留一份即可（内容同类许可文件）。
                "LICENSE",
                "META-INF/LICENSE.md",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
            // ELK 的算法 provider 是 SPI 注册文件：各 jar 内容不同，必须【合并】而非取第一个，
            // 否则只注册到部分布局算法。
            merges += setOf(
                "META-INF/services/org.eclipse.elk.core.data.ILayoutMetaDataProvider",
            )
        }
    }
}

dependencies {
    // 终端模块化（对标 Xed-Editor 的 terminal-emulator / terminal-view 两个独立 module）：
    // :terminal-emulator = 会话内核（进程 + ANSI 过滤），:terminal-view = Compose 终端视图。
    implementation(project(":terminal-emulator"))
    implementation(project(":terminal-view"))

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("io.ktor:ktor-server-core-jvm:3.6.0")
    implementation("io.ktor:ktor-server-cio-jvm:3.6.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("com.squareup.okhttp3:okhttp-sse:5.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.github.rikkahub:markdown:d79a97cc8e")
    implementation("org.jsoup:jsoup:1.23.2")

    // 权限管理: Shizuku (adb 级 shell 权限) + Dhizuku (设备所有者权限)
    // provider 模块提供 rikka.shizuku.ShizukuProvider（Manifest 必需，缺了启动闪退）
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("io.github.iamr0s:Dhizuku-API:2.6.0")

    // ⚠️ 以下 unidbg 配套依赖的版本由 unidbg-api:0.9.9 的 pom 决定，**不要单独升级**：
    //    unicorn 1.0.15 / keystone 0.9.7 /
    //    commons-codec 1.21.0 / commons-collections4 4.5.0 / commons-io 2.21.0 / demumble 1.0.4 / apk-parser 2.6.10
    //    unidbg 用的是本地 patched jar（不参与 Gradle 版本解析），传递依赖必须手工对齐。
    //    keystone 是 zhkl0228 fork 的 Java 绑定，随 unidbg 的 JNI 契约冻结（上游 maven 最新即 0.9.7，见 DEPENDENCIES.md）。
    //    capstone 已从本清单移出 —— 改用本仓库自建绑定跟进官方 capstone 5.0.9 引擎
    //    （源码 third_party/capstone-java；只要保住 capstone.api.* 与 Capstone$OpInfo，unidbg 零改动）。
    //    注：fastjson 已从本清单移出 —— 见下方 fastjson 依赖处（改用 fastjson1-compatible 兼容层）。
    implementation(files("libs/unidbg-api-0.9.9-android-patched.jar"))
    implementation(files("libs/unidbg-android-0.9.9-android-patched.jar"))
    implementation(files("libs/capstone-5.0.9-android-patched.jar"))
    implementation(files("libs/keystone-0.9.7-android-patched.jar"))
    implementation("net.java.dev.jna:jna:5.19.1@aar")
    implementation("commons-codec:commons-codec:1.21.0")  // unidbg 0.9.9 锁定版本，勿单独升级
    implementation("org.apache.commons:commons-collections4:4.5.0")  // unidbg 0.9.9 锁定版本，勿单独升级

    // 逆核: Eclipse ELK 分层布局引擎（CFG 画布可选布局；对应 Exbin ElkLayoutEngine 的官方 Java 库版）
    implementation("org.eclipse.elk:org.eclipse.elk.core:0.12.0")
    implementation("org.eclipse.elk:org.eclipse.elk.alg.layered:0.12.0")
    implementation("org.eclipse.elk:org.eclipse.elk.graph:0.12.0")
    // ELK 0.12 的算法元数据 provider 由 Xtend 生成，引用了 org.eclipse.xtext.xbase.lib 的
    // CollectionLiterals（ELK 的 maven pom 未声明该依赖）—— 不显式补齐，R8 会在
    // LayeredMetaDataProvider.<clinit> 上报 Missing class。
    implementation("org.eclipse.xtext:org.eclipse.xtext.xbase.lib:2.44.0")
    implementation("commons-io:commons-io:2.21.0")  // unidbg 0.9.9 锁定版本，勿单独升级
    // fastjson：1.2.83 → 2.0.65（未单独升级「普通 2.x」，而是官方 fastjson1 兼容层）。
    // com.alibaba:fastjson 的 2.x 系列是 fastjson2 项目发布的 “fastjson1-compatible” 发行版：
    // 保留 com.alibaba.fastjson.* 的类名与 API（JSONObject/JSONArray/JSON/util.IOUtils 全在），
    // 内核换成 fastjson2（AutoType 默认关闭）。因此 unidbg 的
    // McpTools.dispatchTool(String, com.alibaba.fastjson.JSONObject) 反射调用
    // （UnidbgEmulator.sessionNativeToolCall / 其 getDeclaredMethod("dispatchTool", String, ...)）
    // 无需改 unidbg jar 也继续可用。传递依赖：fastjson2-extension → fastjson2。
    implementation("com.alibaba:fastjson:2.0.65")
    implementation("com.lambdapioneer.argon2kt:argon2kt:1.6.0")

    // 逆核: 内置逆向静态分析工具(纯 Java, 作为 MCP 工具聚合)。
    // jadx: dex→java 反编译
    implementation("io.github.skylot:jadx-core:1.5.6")
    implementation("io.github.skylot:jadx-dex-input:1.5.6")
    implementation("org.slf4j:slf4j-api:2.0.20")
    // APKEditor 依赖 ARSCLib: APK 资源解包/回编/合并拆分包(aapt 无关)
    implementation("io.github.reandroid:ARSCLib:1.4.0")
    // APKEditor: 完整 APK 反编译(资源→json/xml)/回编打包/合并拆分包(xapk/apks→单apk)/去混淆重构/加固保护。
    // 纯 Java, aapt 无关, 基于 ARSCLib。补齐 MT 管理器的"改完完整回编成 APK"最后一环。
    implementation("com.github.REAndroid:APKEditor:V1.4.9")
    // smali/baksmali: dex↔smali 汇编(Google 维护的 Android 友好 fork)
    implementation("com.android.tools.smali:smali:3.0.10")
    implementation("com.android.tools.smali:smali-baksmali:3.0.10")
    implementation("com.android.tools.smali:smali-dexlib2:3.0.10")
    // (dex2jar 已移除: 阿里云/central 缺子模块 dex-ir/d2j-external, 且 jadx 已直接 dex→java 更强, 边际价值低)
    // apksig: APK v1/v2/v3 签名(Google 官方, 纯 Java, apksigner 底层库), 用于回编打包后签名
    implementation("com.android.tools.build:apksig:9.4.1")
    // bouncycastle: 运行时生成自签名证书/密钥对(给回编后的 APK 签名用)
    implementation("org.bouncycastle:bcpkix-jdk18on:1.86")
    // DexKit: C++ 实现的高性能 dex 反混淆查找库(带 arm64 native so)。
    // 混淆 App 里靠特征(用了哪些字符串/调用/参数类型)反查被混淆的真实类名/方法名，逆向定位利器。
    implementation("org.luckypray:dexkit:2.3.0")
    implementation("com.github.zhkl0228:demumble:1.0.4")
    implementation("net.dongliu:apk-parser:2.6.10")
    implementation("com.github.zhkl0228:unidbg-unicorn2:0.9.9") {
        exclude(group = "com.github.zhkl0228", module = "unidbg-api")
        exclude(group = "com.github.zhkl0228", module = "capstone")
        exclude(group = "com.github.zhkl0228", module = "keystone")
    }
    // 上游 SOMCP PR #90 同坑修复：exclude unidbg-api 会把其传递依赖
    // com.github.zhkl0228:unicorn(提供 unicorn.Unicorn/UnicornConst/UnicornException) 一并删掉，
    // patched jar 的 UnicornBackend/AbstractARM64Emulator 等 10 个类引用 unicorn.*，
    // 缺依赖时 BackendFactory 回退 UnicornBackend 后 session_open 抛 CNFE unicorn_Unicorn（全 ABI）。
    // 显式补回该 artifact（不会重新引入 unidbg-api，避免与 patched jar 冲突）。
    implementation("com.github.zhkl0228:unicorn:1.0.15")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
}
