plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
}

// Единственный модуль, который видит все impl: здесь Metro собирает контрибуции и генерирует граф.
// Граф объявляется per-platform (androidMain/jvmMain/iosMain), чтобы видеть платформенные контрибуции.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.features.aiEngine.koog.api)
            implementation(projects.features.aiEngine.koog.impl)
            api(projects.features.aiEngine.claude.api)
            implementation(projects.features.aiEngine.claude.impl)
            api(projects.core.common)
            api(projects.features.aiEngine.facade.api)
            implementation(projects.features.aiEngine.facade.impl)
            api(projects.features.aiEngine.pi.api)
            implementation(projects.features.aiEngine.pi.impl)
            implementation(projects.features.aiEngine.acpInterface.impl)
            implementation(projects.features.aiEngine.codex.impl)
            api(projects.features.welcome.api)
            implementation(projects.features.welcome.impl)
            implementation(projects.features.aiStudio.impl)
            implementation(projects.features.togglesPanel.impl)
            implementation(projects.features.aiEngine.authenticator.impl)
            implementation(projects.features.aiEngine.facade.impl)
            implementation(projects.features.aiEngine.connections.impl)
            implementation(projects.features.aiSessionEngineTransfer.impl)
            api(projects.core.di.api)
            api(projects.core.profileFacade.api)
            api(projects.core.navigation.api)
            api(projects.core.datastore.api)
            api(projects.core.secrets.api)
            implementation(projects.core.secrets.impl)
            api(projects.core.stateMachine.api)
            implementation(projects.core.logging)
            implementation(projects.core.mvi)
            implementation(projects.core.network.api)
            implementation(projects.core.featureToggles.api)
            implementation(projects.core.di.impl)
            implementation(projects.core.profileFacade.impl)
            implementation(projects.core.navigation.impl)
            implementation(projects.core.network.impl)
            implementation(projects.core.datastore.impl)
            implementation(projects.core.stateMachine.impl)
            implementation(projects.core.featureToggles.impl)
        }
        jvmTest.dependencies {
            implementation(projects.features.aiEngine.acpInterface.api)
            implementation(projects.features.aiEngine.codex.api)
            implementation(projects.features.aiStudio.api)
            implementation(projects.features.togglesPanel.api)
            implementation(projects.features.aiEngine.connections.api)
            implementation(projects.features.aiSessionEngineTransfer.api)
            implementation(projects.core.di.ext)
            implementation(projects.features.aiEngine.authenticator.api)
            implementation(projects.features.aiEngine.facade.api)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
