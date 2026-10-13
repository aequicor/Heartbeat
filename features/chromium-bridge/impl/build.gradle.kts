plugins {
    alias(libs.plugins.heartbeat.kmp.compose)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
}

compose.resources { packageOfResClass = "io.aequicor.heartbeat.feature.chromiumbridge.impl.resources" }

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.features.chromiumBridge.api)
            implementation(projects.features.chromiumBridge.protocol)
            implementation(projects.features.aiStudio.api)
            implementation(projects.core.common)
            implementation(projects.core.logging)
            implementation(projects.core.featureToggles.api)
            implementation(projects.core.stateMachine.api)
            implementation(projects.core.di.api)
            implementation(projects.core.datastore.api)
            implementation(projects.core.profileFacade.api)
            implementation(projects.core.stateMachine.flowmviExt)
            implementation(projects.core.mvi)
            implementation(projects.core.di.ext)
            implementation(projects.core.navigation.compose)
            implementation(projects.designSystem.components)
            implementation(projects.designSystem.theme)
            implementation(projects.designSystem.layouts)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.collectionsImmutable)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
        }
        commonTest.dependencies {
            implementation(libs.flowmvi.test)
        }
        jvmMain.dependencies {
            implementation(libs.jna.platform)
        }
    }
}

// Tests check the extension's pinned id and the shared protocol fixtures against the app.
val chromiumPluginRoot: String = rootProject.layout.projectDirectory.dir("chromium-plugin").asFile.absolutePath
tasks.withType<Test>().configureEach {
    inputs.file("$chromiumPluginRoot/manifest.json").withPropertyName("chromiumPluginManifest")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("$chromiumPluginRoot/tests/fixtures").withPropertyName("chromiumPluginFixtures")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("heartbeat.chromiumPlugin.root", chromiumPluginRoot)
}
