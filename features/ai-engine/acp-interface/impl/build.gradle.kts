plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
}

kotlin {
    sourceSets.androidMain { kotlin.srcDir("src/mobileMain/kotlin") }
    sourceSets.iosMain { kotlin.srcDir("src/mobileMain/kotlin") }
    sourceSets.commonMain.dependencies {
        implementation(projects.features.aiEngine.acpInterface.api)
        implementation(projects.core.common)
        implementation(projects.core.di.api)
        implementation(projects.core.logging)
    }
}
