plugins {
    alias(libs.plugins.taixu.android.feature)
}

android {
    namespace = "top.wkbin.taixu.feature.workflow"
}

dependencies {
    implementation(project(":core:database"))
    implementation(project(":runtime"))
    implementation(project(":harness"))
    implementation(project(":feature:theme"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
