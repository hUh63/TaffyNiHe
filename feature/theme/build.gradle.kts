plugins {
    alias(libs.plugins.taixu.android.library.compose)
}

android {
    namespace = "top.wkbin.taixu.feature.theme"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.bundles.compose.ui)
    implementation(libs.material.color.utilities)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
