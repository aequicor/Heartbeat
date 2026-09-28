plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.features.aiEngine.facade.api)
            api(projects.core.secrets.api)
            api(projects.core.navigation.api)
            api(libs.kotlinx.coroutines.core)
        }
    }
}
