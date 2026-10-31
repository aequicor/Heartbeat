plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets.androidMain { kotlin.srcDir("src/mobileMain/kotlin") }
    sourceSets.iosMain { kotlin.srcDir("src/mobileMain/kotlin") }
    sourceSets.commonMain.dependencies {
        implementation(projects.features.aiEngine.deepseekHarness.api)
        implementation(projects.features.aiEngine.acpInterface.api)
        implementation(projects.core.di.api)
        implementation(projects.core.common)
        implementation(projects.core.featureToggles.api)
        implementation(projects.core.logging)
        implementation(libs.kotlinx.serialization.json)
    }
    sourceSets.jvmMain.dependencies {
        implementation(libs.snakeyaml)
        implementation(libs.zstd.jni)
    }
}
