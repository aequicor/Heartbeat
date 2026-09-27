plugins {
    alias(libs.plugins.heartbeat.kmp.library)
}

kotlin {
    sourceSets.commonMain.dependencies {
        api(projects.features.aiEngine.facade.api)
    }
}