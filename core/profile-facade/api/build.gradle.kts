plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
}

// Фасад профиля: сессии (открыть/закрыть/восстановить), граф ProfileScope, хранение активного профиля.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.di.api)
        }
    }
}
