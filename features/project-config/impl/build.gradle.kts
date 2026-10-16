plugins {
    alias(libs.plugins.heartbeat.kmp.compose)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
}

compose.resources { packageOfResClass = "io.aequicor.heartbeat.feature.projectconfig.impl.resources" }

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.features.projectConfig.api)
            implementation(projects.features.aiEngine.connections.api)
            implementation(projects.core.common)
            implementation(projects.core.logging)
            implementation(projects.core.di.api)
            implementation(projects.core.di.ext)
            implementation(projects.core.datastore.api)
            implementation(projects.core.featureToggles.api)
            implementation(projects.core.profileFacade.api)
            implementation(projects.core.stateMachine.api)
            implementation(projects.core.stateMachine.flowmviExt)
            implementation(projects.core.mvi)
            implementation(projects.core.navigation.compose)
            implementation(projects.designSystem.components)
            implementation(projects.designSystem.theme)
            implementation(projects.designSystem.layouts)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.collectionsImmutable)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.okio)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
        }
        commonTest.dependencies {
            implementation(libs.flowmvi.test)
        }
        jvmMain.dependencies { implementation(projects.core.desktopDialogs) }
        jvmTest.dependencies {
            implementation(libs.compose.uiTest)
            implementation(compose.desktop.currentOs)
        }
    }
}
