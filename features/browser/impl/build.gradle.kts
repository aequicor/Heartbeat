import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    alias(libs.plugins.heartbeat.kmp.compose)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.heartbeat.jcef)
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
            implementation(projects.core.network.api)
            implementation(libs.apache.commons.compress)
            // Scoped macOS content-view clipping through public JCEF/AppKit APIs.
            implementation(libs.jna.platform)
            // Agent navigation without approval is limited to the same public URLs as web_fetch.
            implementation(projects.features.searchEngine.api)
            // External Chromium browser driven through the Heartbeat extension (ex_control_* tools).
            implementation(projects.features.chromiumBridge.api)
            implementation(libs.jcefmaven.get().toString()) { exclude(group = "me.friwi", module = "jcef-api") }
            implementation(libs.jcef.api)
            // The flat, checksum-pinned API artifact retains upstream JCEF's JOGL dependencies.
            implementation(libs.jcef.jogl)
            implementation(libs.jcef.gluegen)
        }
        jvmTest.dependencies {
            implementation(libs.ktor.client.mock)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.kotlinx.coroutinesSwing)
            heartbeatJcef.nativeBundle?.let { runtimeOnly(it) }
            implementation(libs.compose.uiTest)
            implementation(compose.desktop.currentOs)
        }
    }
}

compose.resources { packageOfResClass = "io.aequicor.heartbeat.feature.browser.impl.resources" }

// Runtime downloads trust the same checked-in artifact hashes as build-time native verification.
tasks.named<ProcessResources>("jvmProcessResources") {
    from(rootProject.layout.projectDirectory.file("native/jcef/source-lock.json")) {
        into("browser")
        rename { "jcef-runtime-lock.json" }
    }
}

heartbeatJcef.registerRuntimeVerification("jvmTestRuntimeClasspath")
// JVM tests run like the desktop host (JBR, module roots, WINDOW layers), after verifying the locked jars.
tasks.named<Test>("jvmTest") { heartbeatJcef.configureNativeRuntime(this) }

// Real Chromium, opt-in and outside `check`: each needs an interactive desktop session and its own JVM.
heartbeatJcef.registerNativeTest(
    name = "browserNativeTest",
    description = "Checks real Chromium surfaces, profile isolation and reopening in an isolated JVM.",
    testClass = "DesktopBrowserNativeTest",
    enablingProperty = "heartbeat.browser.nativeTest",
)
heartbeatJcef.registerNativeTest(
    name = "browserDocumentNativeTest",
    description = "Checks isolated documents in real Chromium: own origin, no network, bridge and data updates.",
    testClass = "DesktopDocumentNativeTest",
    enablingProperty = "heartbeat.browser.documentNativeTest",
)
// Its storage root (with unpacked JCEF natives, locked on Windows until the JVM exits) is cleared by Gradle.
val composeNativeRoot = layout.buildDirectory.dir("native-compose-browser")
val prepareComposeNativeRoot = tasks.register<Delete>("prepareBrowserComposeNativeRoot") {
    delete(composeNativeRoot)
}
val cleanComposeNativeRoot = tasks.register<Delete>("cleanBrowserComposeNativeRoot") {
    delete(composeNativeRoot)
}
heartbeatJcef.registerNativeTest(
    name = "browserComposeNativeTest",
    description = "Checks the production SwingPanel embedding with physical input in an isolated JVM.",
    testClass = "DesktopBrowserComposeNativeTest",
    enablingProperty = "heartbeat.browser.composeNativeTest",
) {
    dependsOn(prepareComposeNativeRoot)
    finalizedBy(cleanComposeNativeRoot)
    systemProperty("heartbeat.browser.composeNativeRoot", composeNativeRoot.get().asFile.absolutePath)
}

// No OS input or capture: checks first native mount, clipping geometry and DOM retention in Compose.
val viewportNativeRoot = layout.buildDirectory.dir("native-document-viewport")
val prepareViewportNativeRoot = tasks.register<Delete>("prepareBrowserViewportNativeRoot") {
    delete(viewportNativeRoot)
}
val cleanViewportNativeRoot = tasks.register<Delete>("cleanBrowserViewportNativeRoot") {
    delete(viewportNativeRoot)
}
heartbeatJcef.registerNativeTest(
    name = "browserViewportNativeTest",
    description = "Checks real Chromium document viewport geometry and retention without OS input or capture.",
    testClass = "DesktopDocumentViewportNativeTest",
    enablingProperty = "heartbeat.browser.viewportNativeTest",
) {
    dependsOn(prepareViewportNativeRoot)
    finalizedBy(cleanViewportNativeRoot)
    systemProperty("heartbeat.browser.composeNativeRoot", viewportNativeRoot.get().asFile.absolutePath)
}
