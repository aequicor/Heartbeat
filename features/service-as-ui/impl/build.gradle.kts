plugins {
    alias(libs.plugins.heartbeat.kmp.compose)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.features.serviceAsUi.api)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
