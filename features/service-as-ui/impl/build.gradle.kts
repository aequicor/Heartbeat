plugins {
    alias(libs.plugins.heartbeat.kmp.compose)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.heartbeat.room)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.features.serviceAsUi.api)
            implementation(projects.features.browser.api)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.okio)
            implementation(projects.core.common)
            implementation(projects.core.datastore.api)
            implementation(projects.core.di.ext)
            implementation(projects.core.featureToggles.api)
            implementation(projects.core.profileFacade.api)
            implementation(projects.core.logging)
            implementation(libs.kotlinx.collectionsImmutable)
            implementation(projects.core.mvi)
            implementation(projects.core.navigation.compose)
            implementation(projects.core.stateMachine.flowmviExt)
            implementation(projects.designSystem.components)
            implementation(projects.designSystem.theme)
            implementation(projects.designSystem.layouts)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
        }
        jvmMain.dependencies { implementation(projects.core.desktopDialogs) }
        commonTest.dependencies { implementation(libs.flowmvi.test) }
        jvmTest.dependencies {
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.compose.uiTest)
            implementation(compose.desktop.currentOs)
        }
    }
}

compose.resources { packageOfResClass = "io.aequicor.heartbeat.feature.serviceasui.impl.resources" }
