plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.kotlinSerialization)
}

// Контракт единых настроек: маршрут с разделом и тогл. Бизнес-машины нет — это чистая навигация.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.navigation.api)
            api(projects.core.featureToggles.api)
        }
        commonTest.dependencies { implementation(libs.kotlinx.serialization.json) }
    }
}
