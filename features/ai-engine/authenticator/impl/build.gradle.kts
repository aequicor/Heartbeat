plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
}

// Реестр источников авторизации профиля (метаданные в KV, значения ключей — в SecretStore),
// явные проверки и встроенные аутентификаторы. Подключается только в :platform-main:di-bundle.
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.features.aiEngine.authenticator.api)
            implementation(projects.core.datastore.api)
            implementation(projects.core.secrets.api)
            implementation(projects.core.di.api)
            implementation(projects.core.logging)
        }
    }
}
