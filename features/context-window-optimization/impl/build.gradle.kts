plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.features.contextWindowOptimization.api)
            implementation(projects.features.aiEngine.facade.api)
            implementation(projects.core.stateMachine.api)
            implementation(projects.core.logging)
            implementation(projects.core.di.api)
            implementation(projects.core.datastore.api)
            implementation(projects.core.featureToggles.api)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
