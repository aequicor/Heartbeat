plugins {
    alias(libs.plugins.heartbeat.kmp.library)
}

// Контракт фича-тоглов: объявление (FeatureToggle), чтение (FeatureToggles), управление локальными переопределениями
// (FeatureToggleControl). Без DataStore и Metro — его можно подключать в api-модули фич.
// Хранение, реестр и логи FT — в :core:feature-toggles:impl.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
        }
    }
}
