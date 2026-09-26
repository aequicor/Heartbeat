plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
}

// Контракты скоупов: маркеры, ScopeHandle, фабрики, сессии профиля, shared-скоупы. Без Essenty/Decompose/FlowMVI.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.core)
        }
    }
}
