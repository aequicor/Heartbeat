import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

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
            if (buildType == NativeBuildType.RELEASE) {
                // Kotlin 2.4.20 exhausts an 8 GiB heap building this phase's whole-program call graph.
                // Keep initializer checks instead; other release optimizations remain enabled.
                freeCompilerArgs += "-Xdisable-phases=RemoveRedundantCallsToStaticInitializersPhase"
            }
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
        jvmTest.dependencies {
            implementation(libs.compose.uiTest)
            implementation(compose.desktop.currentOs)
        }
    }
}
