plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets.commonMain.dependencies {
        implementation(projects.features.aiEngine.kimi.api)
        implementation(projects.features.aiEngine.acpInterface.api)
        implementation(projects.core.di.api)
        implementation(projects.core.common)
        implementation(projects.core.logging)
        implementation(libs.kotlinx.serialization.json)
    }
}
