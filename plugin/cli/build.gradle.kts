plugins {
    alias(libs.plugins.taixu.jvm.library)
    application
}

dependencies {
    implementation(project(":plugin:packages"))
    implementation(project(":core:model"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

application {
    mainClass.set("top.wkbin.taixu.plugin.cli.MainKt")
    applicationName = "taixu-plugin"
}
