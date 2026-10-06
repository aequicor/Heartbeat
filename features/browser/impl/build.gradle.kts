plugins {
    alias(libs.plugins.heartbeat.kmp.compose)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.features.browser.api)
            implementation(projects.core.common)
            implementation(projects.core.di.ext)
            implementation(projects.core.mvi)
            implementation(projects.core.stateMachine.flowmviExt)
            implementation(projects.core.navigation.compose)
            implementation(projects.core.featureToggles.api)
            implementation(projects.core.logging)
            implementation(projects.designSystem.components)
            implementation(projects.designSystem.theme)
            implementation(projects.designSystem.layouts)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
        }
        commonTest.dependencies { implementation(libs.flowmvi.test) }
        jvmMain.dependencies {
            implementation(projects.core.datastore.api)
            implementation(libs.jcefmaven)
        }
        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutinesSwing)
            val hostOs = providers.systemProperty("os.name").get()
            val hostArm = providers.systemProperty("os.arch").get().lowercase() in setOf("arm64", "aarch64")
            val nativeBundle = when {
                hostOs.startsWith("Mac") && hostArm -> libs.jcef.macos.arm64
                hostOs.startsWith("Mac") -> libs.jcef.macos.x64
                hostOs.startsWith("Windows") && hostArm -> libs.jcef.windows.arm64
                hostOs.startsWith("Windows") -> libs.jcef.windows.x64
                else -> null
            }
            nativeBundle?.let { runtimeOnly(it) }
            implementation(libs.compose.uiTest)
            implementation(compose.desktop.currentOs)
        }
    }
}

compose.resources { packageOfResClass = "io.aequicor.heartbeat.feature.browser.impl.resources" }

tasks.withType<Test>().configureEach {
    // Exercise native embedding on the same JBR used by the desktop application.
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.desktop.jdk.get().toInt()))
        vendor.set(JvmVendorSpec.JETBRAINS)
    })
    // JBR's built-in jcef module must not shadow the pinned Maven API on the classpath.
    jvmArgs("--limit-modules=java.se,jdk.unsupported,jdk.httpserver,jdk.crypto.ec,jdk.zipfs,jdk.attach")
    systemProperty("heartbeat.browser.nativeTest", providers.gradleProperty("heartbeat.browser.nativeTest").orElse("false").get())
    if (providers.systemProperty("os.name").get().startsWith("Mac")) {
        jvmArgs(
            "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
        )
    }
}
