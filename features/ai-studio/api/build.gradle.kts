plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.features.aiEngine.facade.api)
            api(projects.core.stateMachine.api)
            api(projects.core.navigation.api)
            api(projects.core.featureToggles.api)
            // Remote studio views exchange the screen model's JSON (StudioRemoteScreens).
            api(libs.kotlinx.serialization.json)
        }
    }
}
