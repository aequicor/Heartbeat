plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
}

// Контракты бандлов desktop: вход бандла, ключи биндингов, здоровье. Без Metro и без реализации реестра.
// Что фундамент отдаёт бандлам (Foundation*Bindings) — только JVM: бандлы существуют только на desktop.
// Типы в них — из api-модулей (контракты); api-модуль фичи, реализацию которой владеет бандл, сюда не попадает.
kotlin {
    sourceSets {
        jvmMain.dependencies {
            api(projects.core.common)
            api(projects.core.di.api)
            api(projects.core.featureToggles.api)
            api(projects.core.network.api)
            api(projects.core.profileFacade.api)
            api(projects.core.datastore.api)
            api(projects.core.stateMachine.api)
            api(projects.core.navigation.api)
            api(projects.core.secrets.api)
            api(projects.features.aiEngine.facade.api)
            api(projects.features.aiEngine.authenticator.api)
            api(projects.features.aiEngine.connections.api)
            api(projects.features.adaptiveSubagents.api)
            api(projects.features.aiStudio.api)
            api(projects.features.attachments.api)
            api(projects.features.projectConfig.api)
            api(projects.features.scheduler.api)
            api(projects.features.searchEngine.api)
        }
    }
}
