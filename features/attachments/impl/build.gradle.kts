plugins {
    alias(libs.plugins.heartbeat.kmp.compose)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.heartbeat.room)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    android { androidResources { enable = true } }
    sourceSets {
        commonMain.dependencies {
            implementation(projects.features.attachments.api)
            implementation(projects.core.di.api)
            implementation(projects.core.di.ext)
            implementation(projects.core.mvi)
            implementation(projects.core.stateMachine.flowmviExt)
            implementation(projects.core.common)
            implementation(projects.core.logging)
            implementation(projects.core.profileFacade.api)
            implementation(projects.core.stateMachine.api)
            implementation(projects.core.navigation.compose)
            implementation(projects.core.featureToggles.api)
            implementation(projects.designSystem.components)
            implementation(projects.designSystem.theme)
            implementation(projects.designSystem.layouts)
            implementation(libs.okio)
            implementation(libs.compose.components.resources)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
        }
        jvmMain.dependencies { implementation(libs.jna.platform) }
        jvmTest.dependencies {
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.compose.uiTest)
            implementation(compose.desktop.currentOs)
        }
    }
}

compose.resources { packageOfResClass = "io.aequicor.heartbeat.feature.attachments.impl.resources" }
