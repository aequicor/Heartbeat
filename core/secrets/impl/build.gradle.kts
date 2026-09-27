plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.secrets.api)
            implementation(projects.core.di.api)
            implementation(projects.core.common)
            implementation(projects.core.logging)
            implementation(projects.core.profileFacade.api)
            implementation(projects.core.datastore.api)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.okio)
        }
        jvmMain.dependencies {
            implementation(libs.jna.platform)
        }
    }
}
