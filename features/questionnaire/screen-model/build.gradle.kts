// Shared serializable screen contract; no domain, navigation or IO.
plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.kmp.wasm)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.flowmvi.core)
            api(libs.kotlinx.collectionsImmutable)
            api(libs.kotlinx.serialization.json)
            // Only for @Immutable on screen types; no Compose UI here.
            implementation(libs.compose.runtime)
        }
    }
}
