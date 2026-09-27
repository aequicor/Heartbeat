plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.flowmvi.core)
            api(projects.core.common)
            implementation(projects.core.logging)
        }
        commonTest.dependencies {
            implementation(libs.flowmvi.test)
        }
    }
}
