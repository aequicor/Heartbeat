plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
}

// Самообновление установленного desktop-приложения из релизов GitHub: проверка последнего релиза, проверенная
// загрузка, установка (macOS — замена бандла из DMG, Windows — тихий установщик Inno Setup) и перезапуск.
// Машина и биндинги есть только на Desktop (jvmMain). Подключается только в :platform-main:di-bundle.
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.features.appUpdate.api)
            implementation(projects.core.common)
            implementation(projects.core.logging)
            implementation(projects.core.network.api)
            implementation(projects.core.stateMachine.api)
            implementation(libs.kotlinx.serialization.json)
        }
        jvmMain.dependencies {
            implementation(projects.core.datastore.api)
            implementation(projects.core.di.api)
            implementation(projects.core.featureToggles.api)
        }
        jvmTest.dependencies {
            implementation(libs.ktor.client.mock)
        }
    }
}
