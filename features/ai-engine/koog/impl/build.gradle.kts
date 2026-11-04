plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.heartbeat.embedded.engine.contract)
}
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.features.aiEngine.koog.api)
            implementation(projects.features.searchEngine.api)
            implementation(projects.core.common)
            implementation(projects.core.di.api)
            implementation(projects.core.logging)
            implementation(projects.core.datastore.api)
            implementation(projects.core.secrets.api)
            implementation(projects.core.network.api)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.koog.executor)
            implementation(libs.koog.openai)
            implementation(libs.koog.anthropic)
            implementation(libs.koog.ollama)
            implementation(libs.koog.ktor)
        }
        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
        }
    }
}

embeddedEngineContract {
    runtimeGroup.set(libs.koog.executor.get().module.group)
    runtimeVersion.set(libs.versions.koog)
}
