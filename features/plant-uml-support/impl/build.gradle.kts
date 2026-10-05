plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
}

// Политика рендера (лимиты, кэш, таймаут) — commonMain без Metro; движок PlantUML и DI — только jvmMain (desktop).
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.features.plantUmlSupport.api)
            implementation(projects.core.logging)
            implementation(projects.core.common)
            implementation(projects.core.di.api)
            implementation(projects.core.featureToggles.api)
        }
    }
}
