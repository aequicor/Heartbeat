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
            implementation(projects.core.common)
            implementation(projects.core.logging)
            implementation(projects.core.featureToggles.api)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
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
