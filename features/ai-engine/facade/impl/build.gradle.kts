plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.heartbeat.room)
    alias(libs.plugins.kotlinSerialization)
}

// Реализация фасада ИИ-движков профиля: каталог движков и подключений, выбор движка по умолчанию, модели,
// индекс сессий, пул runtime и активные сессии на машине ActiveSessionMachineSpec. Подключается только в :platform-main:di-bundle.
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.features.aiEngine.facade.api)
            implementation(projects.features.aiEngine.authenticator.api)
            implementation(projects.core.featureToggles.api)
            implementation(projects.core.datastore.api)
            implementation(projects.core.di.api)
            implementation(projects.core.common)
            implementation(projects.core.logging)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.okio)
        }
        jvmTest.dependencies {
            implementation(libs.androidx.sqlite.bundled)
        }
    }
}
