import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

plugins {
    alias(libs.plugins.heartbeat.kmp.compose)
}

// The native macOS kit contains Java 21 classes; compilation remains on the convention's JDK 17.
val nativeKitTestLauncher = extensions.getByType<JavaToolchainService>().launcherFor {
    languageVersion.set(JavaLanguageVersion.of(libs.versions.desktop.jdk.get().toInt()))
}
tasks.named<Test>("jvmTest") {
    javaLauncher.set(nativeKitTestLauncher)
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
        }
        jvmTest.dependencies {
            implementation(libs.compose.uiTest)
            implementation(compose.desktop.currentOs)
        }
    }
}
