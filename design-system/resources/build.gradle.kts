plugins {
    alias(libs.plugins.heartbeat.kmp.compose)
}

kotlin {
    android {
        // The AGP KMP library target disables Android resources by default; without them the
        // composeResources (.cvr) files never reach the APK and stringResource() throws at runtime.
        androidResources {
            enable = true
        }
    }
    sourceSets {
        commonMain.dependencies {
            api(libs.compose.components.resources)
            implementation(libs.compose.ui)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
        }
    }
}

compose.resources {
    publicResClass = false
    packageOfResClass = "io.aequicor.heartbeat.ds.resources.generated"
    generateResClass = always
}
