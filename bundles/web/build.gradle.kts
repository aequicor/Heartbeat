plugins {
    alias(libs.plugins.heartbeat.bundle)
}

// Бандл «web»: диаграммы PlantUML (первый срез; браузер, chromium-bridge и research-chat переедут позже).
kotlin {
    sourceSets {
        jvmMain.dependencies {
            implementation(projects.features.plantUmlSupport.api)
            implementation(projects.features.plantUmlSupport.impl)
            implementation(projects.core.common)
            implementation(projects.core.logging)
        }
    }
}
