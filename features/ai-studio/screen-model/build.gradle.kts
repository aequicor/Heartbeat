// State, intents and pure reducers of the AI Studio screen, without the studio domain, DI or IO: the app's store
// (ai-studio:impl) and the browser side panel (:chromium-plugin:panel, Kotlin/Wasm) share them.
plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.kmp.wasm)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.flowmvi.core)
            api(libs.kotlinx.collectionsImmutable)
            // Only for @Immutable on screen types; no Compose UI here.
            implementation(libs.compose.runtime)
            implementation(projects.core.logging)
        }
    }
}
