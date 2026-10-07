plugins {
    alias(libs.plugins.heartbeat.kmp.library)
}

// Сервисный контракт подсказок композера без бизнес-машины: запрос/ответ по (токен, контекст).
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.features.aiEngine.facade.api)
            api(projects.core.featureToggles.api)
        }
    }
}
