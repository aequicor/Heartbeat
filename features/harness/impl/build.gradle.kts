plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        jvmMain.dependencies {
            implementation(libs.kotlin.compiler.embeddable)
            implementation(libs.kotlin.scripting.common)
            implementation(libs.kotlin.scripting.jvm)
            implementation(libs.kotlin.scripting.jvm.host)
        }
        commonMain.dependencies {
            implementation(projects.features.harness.api)
            implementation(projects.core.common)
            implementation(projects.core.logging)
            implementation(projects.core.di.api)
            implementation(projects.core.di.ext)
            implementation(projects.core.datastore.api)
            implementation(projects.core.featureToggles.api)
            implementation(projects.core.profileFacade.api)
            implementation(projects.core.stateMachine.api)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.okio)
        }
    }
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "2g"
}
