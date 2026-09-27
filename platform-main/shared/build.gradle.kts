import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.heartbeat.kmp.compose)
}

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
            api(projects.platformMain.root)
            api(projects.core.logging)
            implementation(projects.designSystem.theme)
            implementation(projects.designSystem.components)
            implementation(libs.compose.ui)
        }
    }
}
