plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.features.aiEngine.facade.api)
            api(projects.core.stateMachine.api)
            api(projects.core.navigation.api)
            api(projects.core.featureToggles.api)
            api(libs.kotlinx.serialization.core)
        }
    }
}
