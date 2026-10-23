plugins {
    alias(libs.plugins.heartbeat.bundle)
}

// Бандл «web»: диаграммы PlantUML, встроенный браузер и мост к браузеру Chromium и исследовательский чат.
kotlin {
    sourceSets {
        jvmMain.dependencies {
            implementation(projects.features.plantUmlSupport.api)
            implementation(projects.features.plantUmlSupport.impl)
            implementation(projects.features.browser.api)
            implementation(projects.features.browser.impl)
            implementation(projects.features.chromiumBridge.api)
            implementation(projects.features.chromiumBridge.impl)
            implementation(projects.features.researchChat.api)
            implementation(projects.features.researchChat.impl)
            implementation(projects.features.attachments.api)
            implementation(projects.features.searchEngine.api)
            implementation(projects.features.aiEngine.koog.api)
            implementation(projects.features.aiEngine.facade.api)
            implementation(projects.core.stateMachine.api)
            implementation(projects.core.featureToggles.api)
            implementation(projects.core.di.api)
            implementation(projects.core.common)
            implementation(projects.core.logging)
            implementation(projects.core.mvi)
            implementation(projects.core.datastore.api)
            implementation(projects.core.navigation.api)
            implementation(projects.core.profileFacade.api)
        }
    }
}
