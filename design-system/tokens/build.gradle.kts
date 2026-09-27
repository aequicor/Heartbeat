plugins {
    alias(libs.plugins.heartbeat.kmp.compose)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.compose.ui)
            api(libs.compose.foundation)
        }
    }
}
