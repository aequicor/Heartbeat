plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
}
kotlin {
    sourceSets.commonMain.dependencies {
        implementation(projects.features.aiEngine.claude.api)
            implementation(projects.features.searchEngine.api)
        implementation(projects.core.di.api)
        implementation(projects.core.common)
        implementation(projects.core.logging)
        implementation(libs.kotlinx.serialization.json)
    }
}
