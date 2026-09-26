plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
}

// Реализация сессий профиля. Подключается только в :platform-main:di-bundle.
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.profileFacade.api)
            implementation(projects.core.logging)
        }
    }
}
