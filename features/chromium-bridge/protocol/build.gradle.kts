// Wire models of the Chromium bridge that both ends compile: the app (`chromium-bridge:impl`) and the side panel of
// the extension (`:chromium-plugin:panel`, Kotlin/Wasm). Plain serializable data — no logic, IO or app types.
plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.kmp.wasm)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    explicitApi()
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.core)
        }
    }
}
