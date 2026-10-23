plugins {
    alias(libs.plugins.heartbeat.bundle)
}

// Бандл «engines»: ИИ-движки (Koog, Claude, Codex, Pi, ACP), фасад движков, подключения, панель управления
// и веб-поиск, которым пользуются исследовательские запуски.
kotlin {
    sourceSets {
        jvmMain.dependencies {
            implementation(projects.features.aiEngine.facade.api)
            implementation(projects.features.aiEngine.facade.impl)
            implementation(projects.features.aiEngine.authenticator.api)
            implementation(projects.features.aiEngine.authenticator.impl)
            implementation(projects.features.aiEngine.claude.api)
            implementation(projects.features.aiEngine.claude.impl)
            implementation(projects.features.aiEngine.codex.api)
            implementation(projects.features.aiEngine.codex.impl)
            implementation(projects.features.aiEngine.pi.api)
            implementation(projects.features.aiEngine.pi.impl)
            implementation(projects.features.aiEngine.koog.api)
            implementation(projects.features.aiEngine.koog.impl)
            implementation(projects.features.aiEngine.acpInterface.api)
            implementation(projects.features.aiEngine.acpInterface.impl)
            implementation(projects.features.aiEngine.connections.api)
            implementation(projects.features.aiEngine.connections.impl)
            implementation(projects.features.aiEngine.controlPanel.api)
            implementation(projects.features.aiEngine.controlPanel.impl)
            implementation(projects.features.searchEngine.api)
            implementation(projects.features.searchEngine.impl)
            implementation(projects.core.stateMachine.api)
            implementation(projects.core.featureToggles.api)
            implementation(projects.core.di.api)
            implementation(projects.core.common)
            implementation(projects.core.logging)
            implementation(projects.core.mvi)
            implementation(projects.core.datastore.api)
            implementation(projects.core.navigation.api)
            implementation(projects.core.network.api)
            implementation(projects.core.secrets.api)
            implementation(projects.core.profileFacade.api)
        }
    }
}
