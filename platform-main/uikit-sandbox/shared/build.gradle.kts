import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.heartbeat.kmp.compose)
}

kotlin {
    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.framework {
            baseName = "UIKitSandbox"
            isStatic = true
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(projects.designSystem.catalog)
            implementation(projects.core.logging)
            implementation(libs.compose.ui)
        }
    }
}
