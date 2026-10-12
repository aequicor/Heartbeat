plugins {
    alias(libs.plugins.heartbeat.kmp.compose)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.features.contextWindowOptimization.api)
            implementation(projects.features.aiEngine.facade.api)
            implementation(projects.core.stateMachine.api)
            implementation(projects.core.stateMachine.flowmviExt)
            implementation(projects.core.logging)
            implementation(projects.core.common)
            implementation(projects.core.di.api)
            implementation(projects.core.di.ext)
            implementation(projects.core.mvi)
            implementation(projects.core.navigation.compose)
            implementation(projects.core.datastore.api)
            implementation(projects.core.featureToggles.api)
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

compose.resources { packageOfResClass = "io.aequicor.heartbeat.feature.contextwindowoptimization.impl.resources" }
