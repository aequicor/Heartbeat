plugins {
    alias(libs.plugins.heartbeat.kmp.compose)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.features.serviceAsUi.api)
            implementation(libs.kotlinx.serialization.json)
            implementation(projects.core.common)
            implementation(projects.core.datastore.api)
            implementation(projects.core.di.ext)
            implementation(projects.core.featureToggles.api)
            implementation(projects.core.profileFacade.api)
            implementation(projects.core.logging)
            implementation(libs.kotlinx.collectionsImmutable)
            implementation(projects.core.mvi)
        }
        commonTest.dependencies { implementation(libs.flowmvi.test) }
    }
}
