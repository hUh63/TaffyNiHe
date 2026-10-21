plugins {
    alias(libs.plugins.taixu.android.feature)
}

android {
    namespace = "top.wkbin.taixu.feature.bot"
    resourcePrefix = "fbot_"
}

dependencies {
    implementation(project(":harness"))
    implementation(project(":core:database"))
    implementation(project(":feature:theme"))
    implementation(libs.androidx.activity.compose)

    testImplementation(libs.bundles.test.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
