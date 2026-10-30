plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets.androidMain { kotlin.srcDir("src/mobileMain/kotlin") }
    sourceSets.iosMain { kotlin.srcDir("src/mobileMain/kotlin") }
    sourceSets.commonMain.dependencies {
        implementation(projects.features.aiEngine.kimi.api)
        implementation(projects.features.aiEngine.acpInterface.api)
        implementation(projects.core.di.api)
        implementation(projects.core.common)
        implementation(projects.core.featureToggles.api)
        implementation(projects.core.logging)
        implementation(projects.core.network.api)
        implementation(libs.kotlinx.serialization.json)
    }
    sourceSets.jvmTest.dependencies {
        implementation(libs.ktor.client.mock)
        implementation(libs.ktor.client.okhttp)
    }
}

// Opt-in telemetry check: reads native model capacities and account quota, never sends an LLM prompt.
val regularJvmTest = tasks.named<Test>("jvmTest")
tasks.register<Test>("kimiLocalApiLiveTest") {
    group = "verification"
    description = "Checks the installed Kimi local API without generating model output."
    testClassesDirs = regularJvmTest.get().testClassesDirs
    classpath = regularJvmTest.get().classpath
    filter { includeTestsMatching("*.KimiLocalApiLiveTest") }
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Live checks must reach the installed CLI") { true }
    systemProperty("heartbeat.kimi.localApiLiveTest", "true")
    providers.gradleProperty("heartbeat.kimi.resourcesDir").orNull?.let {
        systemProperty("compose.application.resources.dir", it)
    }
    testLogging { events("passed", "failed", "skipped") }
}
