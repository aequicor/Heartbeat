import io.aequicor.heartbeat.buildlogic.VerifyBrowserRuntime

plugins {
    alias(libs.plugins.heartbeat.kmp.compose)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
}

val browserHostOs = providers.systemProperty("os.name").get()
val browserHostArm = providers.systemProperty("os.arch").get().lowercase() in setOf("arm64", "aarch64")
val browserHostPlatform = when {
    browserHostOs.startsWith("Mac") && browserHostArm -> "macosx-arm64"
    browserHostOs.startsWith("Mac") -> "macosx-amd64"
    browserHostOs.startsWith("Windows") && browserHostArm -> "windows-arm64"
    browserHostOs.startsWith("Windows") -> "windows-amd64"
    else -> null
}
val browserNativeBundle = when (browserHostPlatform) {
    "macosx-arm64" -> libs.jcef.macos.arm64
    "macosx-amd64" -> libs.jcef.macos.x64
    "windows-arm64" -> libs.jcef.windows.arm64
    "windows-amd64" -> libs.jcef.windows.x64
    else -> null
}
val browserRuntimeVerification by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
dependencies {
    browserRuntimeVerification(libs.jcef.api)
    browserNativeBundle?.let { browserRuntimeVerification(it) }
}
val verifyBrowserRuntime = tasks.register<VerifyBrowserRuntime>("verifyBrowserRuntime") {
    group = "verification"
    version.set(libs.versions.jcef.native)
    platform.set(browserHostPlatform.orEmpty())
    manifest.set(rootProject.layout.projectDirectory.file("native/jcef/source-lock.json"))
    patch.set(rootProject.layout.projectDirectory.file("native/jcef/request-context-settings.patch"))
    artifacts.from(browserRuntimeVerification)
    onlyIf { (it as VerifyBrowserRuntime).platform.get().isNotEmpty() }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.features.browser.api)
            implementation(projects.features.aiEngine.facade.api)
            implementation(projects.core.profileFacade.api)
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
            implementation(libs.jcefmaven.get().toString()) { exclude(group = "me.friwi", module = "jcef-api") }
            implementation(libs.jcef.api)
            // The flat, checksum-pinned API artifact retains upstream JCEF's JOGL dependencies.
            implementation(libs.jcef.jogl)
            implementation(libs.jcef.gluegen)
        }
        jvmTest.dependencies {
            implementation(libs.jna.platform)
            implementation(libs.kotlinx.coroutinesSwing)
            browserNativeBundle?.let { runtimeOnly(it) }
            implementation(libs.compose.uiTest)
            implementation(compose.desktop.currentOs)
        }
    }
}

compose.resources { packageOfResClass = "io.aequicor.heartbeat.feature.browser.impl.resources" }

tasks.withType<Test>().configureEach {
    dependsOn(verifyBrowserRuntime)
    // Match the desktop host: same-canvas dialogs are obscured by the native Chromium surface.
    systemProperty("compose.layers.type", "WINDOW")
    // Direct3D's immediate reshape rendering reenters WINDOW layers; match configureDesktopRendering.
    if (providers.systemProperty("os.name").get().startsWith("Windows")) {
        val architecture = providers.systemProperty("os.arch").get().lowercase()
        systemProperty("skiko.renderApi", if (architecture in listOf("aarch64", "arm64")) "SOFTWARE_FAST" else "OPENGL")
    }
    // Exercise native embedding on the same JBR used by the desktop application.
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.desktop.jdk.get().toInt()))
        vendor.set(JvmVendorSpec.JETBRAINS)
    })
    // JBR's built-in jcef module must not shadow the pinned Maven API on the classpath.
    jvmArgs("--limit-modules=java.se,jdk.unsupported,jdk.httpserver,jdk.crypto.ec,jdk.zipfs,jdk.attach")
    systemProperty("heartbeat.browser.nativeTest", providers.gradleProperty("heartbeat.browser.nativeTest").orElse("false").get())
    systemProperty(
        "heartbeat.browser.composeNativeTest",
        providers.gradleProperty("heartbeat.browser.composeNativeTest").orElse("false").get(),
    )
    if (providers.systemProperty("os.name").get().startsWith("Mac")) {
        jvmArgs(
            "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
        )
    }
}
