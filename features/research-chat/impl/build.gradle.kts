plugins {
    alias(libs.plugins.heartbeat.kmp.compose)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.features.researchChat.api)
            implementation(projects.features.attachments.api)
            implementation(projects.features.aiEngine.koog.api)
            implementation(projects.features.searchEngine.api)
            implementation(projects.core.datastore.api)
            implementation(projects.core.common)
            implementation(projects.core.di.ext)
            implementation(projects.core.mvi)
            implementation(projects.core.stateMachine.flowmviExt)
            implementation(projects.core.navigation.compose)
            implementation(projects.core.logging)
            implementation(projects.designSystem.components)
            implementation(projects.designSystem.theme)
            implementation(projects.designSystem.layouts)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
        }
        commonTest.dependencies { implementation(libs.flowmvi.test) }
        jvmTest.dependencies {
            implementation(libs.compose.uiTest)
            implementation(compose.desktop.currentOs)
        }
    }
}

compose.resources { packageOfResClass = "io.aequicor.heartbeat.feature.researchchat.impl.resources" }
