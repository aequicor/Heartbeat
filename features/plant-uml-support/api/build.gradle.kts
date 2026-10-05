plugins {
    alias(libs.plugins.heartbeat.kmp.library)
}

// Сервисный контракт рендера PlantUML без бизнес-машины: типы запроса/результата и PlantUmlRenderer.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
        }
    }
}
