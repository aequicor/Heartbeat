plugins {
    alias(libs.plugins.heartbeat.kmp.compose)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.compose.foundation)
            api(libs.compose.ui)
            api(libs.kotlinx.collectionsImmutable)
            implementation(projects.designSystem.theme)
            implementation(projects.designSystem.layouts)
            implementation(projects.designSystem.adaptive)
            implementation(projects.core.logging)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.animation)
            implementation(libs.markdown)
            implementation(libs.haze)
            implementation(libs.haze.blur)
        }
        jvmTest.dependencies {
            implementation(libs.compose.uiTest)
            implementation(compose.desktop.currentOs)
        }
    }
}
