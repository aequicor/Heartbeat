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
            implementation(projects.core.featureToggles.api)
        }
        jvmMain.dependencies {
            implementation(projects.core.common)
            implementation(projects.core.di.api)
            implementation(libs.plantuml.mit)
        }
    }
}

// PlantUML draws with AWT; tests run without a display.
tasks.withType<Test>().configureEach {
    systemProperty("java.awt.headless", "true")
}
