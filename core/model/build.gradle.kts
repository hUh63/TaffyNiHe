plugins {
    alias(libs.plugins.taixu.jvm.library)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
