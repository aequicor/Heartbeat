plugins {
    alias(libs.plugins.heartbeat.kmp.compose)
    alias(libs.plugins.heartbeat.kmp.wasm)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.designSystem.tokens)
            api(projects.designSystem.adaptive)
            implementation(projects.core.logging)
        }
        jvmTest.dependencies {
            implementation(libs.compose.uiTest)
            implementation(compose.desktop.currentOs)
        }
    }
}
