plugins {
    alias(libs.plugins.heartbeat.bundle)
}

// Бандл «shell»: окно настроек, приветствие, панель тогглов, журналы приложения и самообновление.
kotlin {
    sourceSets {
        jvmMain.dependencies {
            implementation(projects.features.settings.api)
            implementation(projects.features.settings.impl)
            implementation(projects.features.welcome.api)
            implementation(projects.features.welcome.impl)
            implementation(projects.features.togglesPanel.api)
            implementation(projects.features.togglesPanel.impl)
            implementation(projects.features.appLogs.api)
            implementation(projects.features.appLogs.impl)
            implementation(projects.features.appUpdate.api)
            implementation(projects.features.appUpdate.impl)
            implementation(projects.core.desktopDialogs)
            implementation(projects.core.stateMachine.api)
            implementation(projects.core.featureToggles.api)
            implementation(projects.core.di.api)
            implementation(projects.core.common)
            implementation(projects.core.logging)
            implementation(projects.core.mvi)
            implementation(projects.core.datastore.api)
            implementation(projects.core.navigation.api)
            implementation(projects.core.network.api)
            implementation(projects.core.profileFacade.api)
        }
    }
}
