plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
}

// Реализация навигации: хосты на childStack/childPanels, реестры маршрутов и deep links, результаты, логи NAV.
// Подключается только в :platform-main:di-bundle.
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.navigation.api)
            implementation(projects.core.di.api)
            implementation(projects.core.logging)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
