plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.featureToggles.api)
            implementation(libs.kotlinx.serialization.core)
        }
    }
}
