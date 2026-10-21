plugins {
    alias(libs.plugins.taixu.android.library)
}

android {
    namespace = "top.wkbin.taixu.core.common"
}

dependencies {
    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.mlkit.translate)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}
