import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.heartbeat.kmp.compose)
}

compose.resources { packageOfResClass = "io.aequicor.heartbeat.platform.shared.resources" }

// Общий вход приложения: создание HeartbeatRoot и его Compose-рендер.
// Подключают platform-main:android и platform-main:desktop; для iOS собирается статический framework `Shared`
// (Xcode-проект platform-main/ios вызывает :platform-main:shared:embedAndSignAppleFrameworkForXcode).
kotlin {
    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(projects.features.aiStudio.api)
            api(projects.platformMain.root)
            api(projects.core.logging)
            implementation(projects.designSystem.theme)
            implementation(projects.designSystem.components)
            implementation(projects.designSystem.layouts)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
        }
    }
}
