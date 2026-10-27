plugins {
    alias(libs.plugins.heartbeat.kmp.compose)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
}

// Самообновление установленного desktop-приложения из релизов GitHub: проверка последнего релиза, проверенная
// загрузка, установка (macOS — замена бандла из DMG, Windows — тихий установщик Inno Setup) и перезапуск.
// Машина и биндинги есть только на Desktop (jvmMain), там же страница «Обновления» настроек: версия сборки, бандлы
// поезда и ручная проверка. Подключается только в :platform-main:di-bundle и бандле shell.
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.features.appUpdate.api)
            implementation(projects.core.common)
            implementation(projects.core.di.api)
            implementation(projects.core.di.ext)
            implementation(projects.core.logging)
            implementation(projects.core.mvi)
            implementation(projects.core.navigation.compose)
            implementation(projects.core.network.api)
            implementation(projects.core.stateMachine.api)
            implementation(projects.core.stateMachine.flowmviExt)
            implementation(projects.designSystem.components)
            implementation(projects.designSystem.theme)
            implementation(projects.designSystem.layouts)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
        }
        jvmMain.dependencies {
            implementation(projects.core.datastore.api)
            implementation(projects.core.featureToggles.api)
        }
        jvmTest.dependencies {
            implementation(libs.ktor.client.mock)
            implementation(libs.compose.uiTest)
            implementation(compose.desktop.currentOs)
        }
    }
}

compose.resources { packageOfResClass = "io.aequicor.heartbeat.feature.appupdate.impl.resources" }
