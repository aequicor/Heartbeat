plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
}

// Рантайм state-machine на KStateMachine: запуск машин в скоупе фичи, реестр живых машин, логи SM/<name>.
// Подключается только в :platform-main:di-bundle.
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.stateMachine.api)
            implementation(projects.core.di.api)
            implementation(projects.core.logging)
            implementation(libs.kstatemachine)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
