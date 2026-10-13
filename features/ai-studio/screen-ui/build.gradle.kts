// Composables and strings of the AI Studio screen over the screen model: the desktop window (ai-studio:impl) and the
// browser side panel (:chromium-plugin:panel, Kotlin/Wasm) draw the same screen.
plugins {
    alias(libs.plugins.heartbeat.kmp.compose)
    alias(libs.plugins.heartbeat.kmp.wasm)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.features.aiStudio.screenModel)
            implementation(projects.core.logging)
            implementation(projects.designSystem.components)
            implementation(projects.designSystem.theme)
            implementation(projects.designSystem.layouts)
            implementation(libs.kotlinx.datetime)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.animation)
            implementation(libs.compose.uiToolingPreview)
        }
        jvmTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.compose.uiTest)
            implementation(compose.desktop.currentOs)
        }
    }
}

// The package stays the one of ai-studio:impl until the package rename step; impl tests find texts through it.
compose.resources {
    packageOfResClass = "io.aequicor.heartbeat.feature.aistudio.impl.resources"
    publicResClass = true
}
