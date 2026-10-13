plugins {
    alias(libs.plugins.heartbeat.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.stateMachine.api)
            api(projects.core.featureToggles.api)
        }
    }
}
