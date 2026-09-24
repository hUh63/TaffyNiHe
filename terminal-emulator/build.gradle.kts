plugins {
    // AGP 9 起内置 Kotlin 支持，无需（也不允许）再应用 org.jetbrains.kotlin.android
    id("com.android.library")
}

android {
    namespace = "com.soreverse.mcp.termemu"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
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

    lint {
        checkReleaseBuilds = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
