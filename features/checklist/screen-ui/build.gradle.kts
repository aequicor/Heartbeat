// Same stateless screen for the native window and the Wasm panel.
plugins {
    alias(libs.plugins.heartbeat.kmp.compose)
    alias(libs.plugins.heartbeat.kmp.wasm)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.features.checklist.screenModel)
            implementation(projects.designSystem.components)
            implementation(projects.designSystem.theme)
            implementation(projects.designSystem.layouts)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
        }
        jvmTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.compose.uiTest)
            implementation(compose.desktop.currentOs)
        }
    }
}

compose.resources {
    packageOfResClass = "io.aequicor.heartbeat.feature.checklist.impl.resources"
    publicResClass = true
}
