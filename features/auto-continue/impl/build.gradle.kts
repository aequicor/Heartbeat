plugins {
    alias(libs.plugins.heartbeat.kmp.compose)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
}

// Продолжатель диалога: эффекты машины (служебный промпт без инструментов, отсчёт, отправка), настройки профиля
// и их раздел в окне настроек.
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.features.autoContinue.api)
            implementation(projects.features.aiEngine.facade.api)
            implementation(projects.features.aiEngine.connections.api)
            implementation(projects.core.stateMachine.api)
            implementation(projects.core.stateMachine.flowmviExt)
            implementation(projects.core.logging)
            implementation(projects.core.di.api)
            implementation(projects.core.di.ext)
            implementation(projects.core.mvi)
            implementation(projects.core.navigation.compose)
            implementation(projects.core.datastore.api)
            implementation(projects.core.featureToggles.api)
            implementation(projects.designSystem.components)
            implementation(projects.designSystem.theme)
            implementation(projects.designSystem.layouts)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.compose.components.resources)
        }
        commonTest.dependencies { implementation(libs.flowmvi.test) }
    }
}

compose.resources { packageOfResClass = "io.aequicor.heartbeat.feature.autocontinue.impl.resources" }
