plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.features.aiEngine.authenticator.api)
            api(projects.core.stateMachine.api)
            api(projects.core.featureToggles.api)
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
