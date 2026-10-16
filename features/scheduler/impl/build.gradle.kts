import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        jvmMain.dependencies {
            implementation(libs.jna.platform)
        }
        commonMain.dependencies {
            implementation(projects.features.scheduler.api)
            implementation(projects.core.common)
            implementation(projects.core.datastore.api)
            implementation(projects.core.di.api)
            implementation(projects.core.featureToggles.api)
            implementation(projects.core.profileFacade.api)
            implementation(projects.core.stateMachine.api)
            implementation(projects.core.logging)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

// Native process failures need the assertion message; the default summary only reports the coroutine entry line.
tasks.named<Test>("jvmTest") {
    testLogging.exceptionFormat = TestExceptionFormat.FULL
}
