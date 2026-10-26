plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
}

// Тестовая фикстура spike-тестов Metro (`MetroBundleSpikeTest`): публичный `@ContributesBinding` в отдельном модуле.
// Подключается только как jvmTest-зависимость `:core:bundles:impl`, в приложение не попадает.
