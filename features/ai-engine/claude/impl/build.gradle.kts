plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
}
kotlin {
    sourceSets.commonMain.dependencies {
        implementation(projects.features.aiEngine.claude.api)
        implementation(projects.features.searchEngine.api)
        implementation(projects.core.di.api)
        implementation(projects.core.common)
        implementation(projects.core.logging)
        implementation(projects.core.datastore.api)
        implementation(libs.kotlinx.serialization.json)
    }
}

// Real Claude Code CLI, opt-in and outside `check`: it needs an authenticated CLI and spends model quota.
val regularJvmTest = tasks.named<Test>("jvmTest")
tasks.register<Test>("claudeLiveTest") {
    group = "verification"
    description = "Runs the regression scenarios against the real Claude Code CLI (-Pheartbeat.claude.executable=<path>)."
    testClassesDirs = regularJvmTest.get().testClassesDirs
    classpath = regularJvmTest.get().classpath
    filter { includeTestsMatching("*.ClaudeLiveE2eTest") }
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Live checks must reach the real CLI") { true }
    systemProperty("heartbeat.claude.liveTest", "true")
    systemProperty("heartbeat.claude.liveReport", layout.buildDirectory.file("claude-live-report.txt").get().asFile.path)
    providers.gradleProperty("heartbeat.claude.executable").orNull?.let {
        systemProperty("heartbeat.claude.executable", it)
    }
    testLogging { events("passed", "failed", "skipped") }
}
