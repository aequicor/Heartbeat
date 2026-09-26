plugins {
    alias(libs.plugins.heartbeat.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // implementation: Napier не должен быть виден за пределами core:logging (RawLoggingCall)
            implementation(libs.napier)
        }
    }
}
