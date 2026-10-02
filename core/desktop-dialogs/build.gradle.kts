plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
}

// Нативные системные диалоги выбора файла и папки (Explorer на Windows, панели Finder на macOS).
// Контракт — в commonMain; реализация есть только на Desktop (JVM), мобильные платформы показывают
// свои системные пикеры внутри фич.
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(projects.core.common)
            implementation(projects.core.logging)
        }
        jvmMain.dependencies {
            implementation(libs.jna.platform)
        }
    }
}
