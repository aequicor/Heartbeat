plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
}
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.features.aiEngine.koog.api)
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
