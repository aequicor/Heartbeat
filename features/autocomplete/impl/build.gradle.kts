plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
}

// Источники подсказок без UI: попап рисует вызывающий экран компонентами дизайн-системы.
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.features.autocomplete.api)
            implementation(projects.features.agentLearning.api)
            implementation(projects.features.aiEngine.facade.api)
            implementation(projects.features.attachments.api)
            implementation(projects.core.common)
            implementation(projects.core.logging)
            implementation(projects.core.di.api)
            implementation(projects.core.di.ext)
            implementation(projects.core.featureToggles.api)
            implementation(projects.core.stateMachine.api)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
