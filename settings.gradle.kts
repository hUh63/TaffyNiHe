pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/central")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://jitpack.io")
    }
}

rootProject.name = "SoReverseNativeMcp"
include(":app")

// 终端模块化（对标 Xed-Editor 的 terminal-emulator / terminal-view 两个独立 module）：
//  - :terminal-emulator 终端内核（会话进程管理 + ANSI 过滤），无 Compose 依赖
//  - :terminal-view     终端 Compose 视图（TerminalScreen），依赖前者
include(":terminal-emulator")
include(":terminal-view")
