plugins {
    alias(libs.plugins.heartbeat.bundle)
}

// Бандл «web»: диаграммы PlantUML, встроенный браузер и мост к браузеру Chromium (research-chat переедет позже).
kotlin {
    sourceSets {
        jvmMain.dependencies {
            implementation(projects.features.plantUmlSupport.api)
            implementation(projects.features.plantUmlSupport.impl)
            implementation(projects.features.browser.api)
            implementation(projects.features.browser.impl)
            implementation(projects.features.chromiumBridge.api)
            implementation(projects.features.chromiumBridge.impl)
            implementation(projects.features.aiEngine.facade.api)
            implementation(projects.core.common)
            implementation(projects.core.logging)
            implementation(projects.core.mvi)
            implementation(projects.core.datastore.api)
            implementation(projects.core.navigation.api)
            implementation(projects.core.profileFacade.api)
        }
    }
}
