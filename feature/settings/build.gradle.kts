plugins {
    alias(libs.plugins.taixu.android.feature)
}

android {
    namespace = "top.wkbin.taixu.feature.settings"
}

dependencies {
    implementation(project(":plugin:ui"))
    implementation(project(":feature:theme"))
    implementation(project(":core:datastore"))
    implementation(project(":core:database"))
    implementation(project(":core:network"))
    implementation(project(":runtime"))
    implementation(project(":tools"))
    implementation(project(":harness"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bundles.coil)
    // 系统自检直接读取 AppDatabase 的 openHelper 判定 Room 是否可用，需 room-runtime 类型可见。
    implementation(libs.androidx.room.runtime)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
