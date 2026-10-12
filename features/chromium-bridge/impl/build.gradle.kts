plugins {
    alias(libs.plugins.heartbeat.kmp.compose)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
}

compose.resources { packageOfResClass = "io.aequicor.heartbeat.feature.chromiumbridge.impl.resources" }

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.features.chromiumBridge.api)
            implementation(projects.core.common)
            implementation(projects.core.logging)
            implementation(projects.core.featureToggles.api)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
