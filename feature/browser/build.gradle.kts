plugins {
    alias(libs.plugins.taixu.android.feature)
}

android {
    namespace = "top.wkbin.taixu.feature.browser"
    resourcePrefix = "fbrowser_"
}

dependencies {
    implementation(project(":plugin:ui"))
    implementation(project(":core:browser"))
    implementation(project(":core:datastore"))
    implementation(project(":runtime:browser"))
    implementation(project(":feature:theme"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.bundles.coil)
}
