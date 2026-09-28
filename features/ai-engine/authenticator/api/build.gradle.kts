plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.secrets.api)
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
