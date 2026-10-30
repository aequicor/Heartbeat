plugins {
    alias(libs.plugins.heartbeat.kmp.library)
}

kotlin {
    sourceSets.commonMain.dependencies {
        api(projects.features.aiEngine.facade.api)
        api(projects.features.aiEngine.authenticator.api)
        implementation(projects.core.featureToggles.api)
    }
}
