plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.features.feedback.api)
            implementation(projects.core.common)
            implementation(projects.core.datastore.api)
            implementation(projects.core.di.api)
            implementation(projects.core.featureToggles.api)
            implementation(projects.core.profileFacade.api)
            implementation(projects.core.stateMachine.api)
            implementation(projects.core.logging)
        }
    }
}
