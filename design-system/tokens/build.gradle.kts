plugins {
    alias(libs.plugins.heartbeat.kmp.compose)
    alias(libs.plugins.heartbeat.kmp.wasm)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.compose.ui)
            api(libs.compose.foundation)
        }
    }
}
