plugins {
    alias(libs.plugins.taixu.android.library)
}

android {
    namespace = "top.wkbin.taixu.runtime.browser"
    resourcePrefix = "rtbrowser_"
}

dependencies {
    api(project(":core:browser"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:datastore"))
    implementation(project(":core:security"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.koin.core)
    testImplementation(libs.bundles.test.robolectric)
    testImplementation(libs.kotlinx.coroutines.core)
    // CDP WebSocket 会话测试：MockWebServer 的 withWebSocketUpgrade 提供真实 WS 服务端
    testImplementation(libs.okhttp.mockwebserver)
}
