plugins {
    alias(libs.plugins.heartbeat.kmp.compose)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
}

compose.resources { packageOfResClass = "io.aequicor.heartbeat.feature.searchengine.impl.resources" }

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.features.searchEngine.api)
            implementation(projects.core.datastore.api)
            implementation(projects.core.secrets.api)
            implementation(projects.core.network.api)
            implementation(projects.core.di.api)
            implementation(projects.core.navigation.compose)
            implementation(projects.core.mvi)
            implementation(projects.core.logging)
            implementation(projects.designSystem.components)
            implementation(projects.designSystem.theme)
            implementation(projects.designSystem.layouts)
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.compose.components.resources)
        }
        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
        }
    }
}
