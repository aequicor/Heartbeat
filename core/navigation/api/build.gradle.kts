plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
}

// Контракт навигации: Route, Navigator, хосты, результаты, deep links. Без Compose — его видят api фич.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.di.api)
            api(libs.decompose)
            api(libs.kotlinx.serialization.core)
            api(libs.kotlinx.coroutines.core)
        }
    }
}
