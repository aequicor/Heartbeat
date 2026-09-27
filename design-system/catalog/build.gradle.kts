plugins {
    alias(libs.plugins.heartbeat.kmp.compose)
}

// The macOS UI kit ships Java 21 classes; common/Android bytecode remains on the shared convention.
tasks.withType<Test>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.desktop.jdk.get()))
    })
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.compose.ui)
            api(projects.designSystem.adaptive)
            api(projects.designSystem.theme)
            implementation(projects.designSystem.tokens)
            implementation(projects.designSystem.components)
            implementation(projects.designSystem.layouts)
            implementation(projects.designSystem.resources)
            implementation(projects.core.logging)
            implementation(libs.compose.foundation)
            implementation(libs.compose.animation)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.collectionsImmutable)
        }
        jvmTest.dependencies {
            implementation(libs.compose.uiTest)
            implementation(compose.desktop.currentOs)
        }
    }
}
