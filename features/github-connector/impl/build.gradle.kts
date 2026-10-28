plugins {
    alias(libs.plugins.heartbeat.kmp.compose)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
}

// Коннектор GitHub: поиск gh, аккаунтов и репозиториев, проверка выбора, сохранение подключения профиля
// и раздел настроек «Инструменты Heartbeat».
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.features.githubConnector.api)
            implementation(projects.core.common)
            implementation(projects.core.di.api)
            implementation(projects.core.di.ext)
            implementation(projects.core.mvi)
            implementation(projects.core.stateMachine.api)
            implementation(projects.core.stateMachine.flowmviExt)
            implementation(projects.core.navigation.compose)
            implementation(projects.core.datastore.api)
            implementation(projects.core.featureToggles.api)
            implementation(projects.core.logging)
            implementation(projects.designSystem.components)
            implementation(projects.designSystem.theme)
            implementation(projects.designSystem.layouts)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.collectionsImmutable)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
        }
        jvmTest.dependencies {
            implementation(libs.compose.uiTest)
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.test)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

compose.resources { packageOfResClass = "io.aequicor.heartbeat.feature.githubconnector.impl.resources" }
