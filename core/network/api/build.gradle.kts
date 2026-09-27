plugins {
    alias(libs.plugins.heartbeat.kmp.library)
}

// Контракт сети: HttpClient (Ktor) для API-классов фич, NetworkConfig, NetworkException и networkResult { }.
// Engine, плагины и логирование — в :core:network:impl.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
        }
    }
}
