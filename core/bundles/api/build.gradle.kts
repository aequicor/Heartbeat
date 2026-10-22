plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
}

// Контракты бандлов desktop: вход бандла, ключи биндингов, здоровье. Без Metro и без реализации реестра.
// Что фундамент отдаёт бандлам (FoundationAppBindings) — только JVM: бандлы существуют только на desktop.
kotlin {
    sourceSets {
        jvmMain.dependencies {
            api(projects.core.di.api)
            api(projects.core.featureToggles.api)
            api(projects.core.network.api)
        }
    }
}
