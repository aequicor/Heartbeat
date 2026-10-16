plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.kotlinSerialization)
}

// Контракт панели управления движками: маршруты раздела «Движки» и панели движка, машина операций панели.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.features.aiEngine.facade.api)
            api(projects.core.stateMachine.api)
            api(projects.core.navigation.api)
            api(libs.kotlinx.serialization.core)
        }
    }
}
