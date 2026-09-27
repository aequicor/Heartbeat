plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
}

// Фича-тоглы: реестр (Metro-мультибиндинг Set<FeatureToggle<*>>), локальные переопределения в app-хранилище
// core:datastore (kv core_feature_toggles), логи FT. Подключается только в :platform-main:di-bundle.
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.featureToggles.api)
            implementation(projects.core.datastore.api)
            implementation(projects.core.di.api)
            implementation(projects.core.logging)
        }
    }
}
