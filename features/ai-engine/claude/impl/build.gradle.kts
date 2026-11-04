import io.aequicor.heartbeat.buildlogic.WriteClaudeBuildContract

plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
}

// What this adapter was compiled from: content hashes of the facade/core contract and the Claude connector sources.
// No Git state, timestamps or absolute paths enter the generated constants.
val claudeBuildContract = tasks.register<WriteClaudeBuildContract>("generateClaudeBuildContract") {
    sourceRoot.set(rootProject.layout.projectDirectory)
    heartbeatSources.from(
        rootProject.fileTree(rootDir) {
            include("features/ai-engine/facade/*/src/*Main/kotlin/**")
            include("features/ai-engine/facade/*/build.gradle.kts")
            include("features/ai-engine/authenticator/*/src/*Main/kotlin/**")
            include("features/ai-engine/authenticator/*/build.gradle.kts")
            include("core/**/src/*Main/kotlin/**", "core/**/build.gradle.kts")
            include("features/**/api/src/*Main/kotlin/**", "features/**/api/build.gradle.kts")
            include(
                "gradle/libs.versions.toml",
                "gradle.properties",
                "settings.gradle.kts",
                "build.gradle.kts",
                "build-logic/settings.gradle.kts",
                "build-logic/convention/build.gradle.kts",
                "build-logic/gradle.properties",
                "build-logic/convention/src/main/kotlin/**",
            )
            exclude("**/build/**")
        },
    )
    connectorSources.from(
        fileTree(projectDir) {
            include("src/*Main/kotlin/**", "build.gradle.kts")
            exclude("**/build/**")
        },
        fileTree(projectDir.parentFile.resolve("api")) {
            include("src/*Main/kotlin/**", "build.gradle.kts")
            exclude("**/build/**")
        },
        rootProject.fileTree(rootDir) {
            include(
                "gradle/libs.versions.toml",
                "gradle.properties",
                "settings.gradle.kts",
                "build.gradle.kts",
                "build-logic/settings.gradle.kts",
                "build-logic/convention/build.gradle.kts",
                "build-logic/gradle.properties",
                "build-logic/convention/src/main/kotlin/**",
            )
            exclude("**/build/**")
        },
    )
    packageName.set("io.aequicor.heartbeat.feature.aiengine.claude.impl.data.generated")
    outputDirectory.set(layout.buildDirectory.dir("generated/claudeBuildContract"))
}

kotlin {
    sourceSets {
        jvmMain { kotlin.srcDir(claudeBuildContract.flatMap { it.outputDirectory }) }
        commonMain.dependencies {
            implementation(projects.features.aiEngine.claude.api)
            implementation(projects.features.searchEngine.api)
            implementation(projects.core.di.api)
            implementation(projects.core.common)
            implementation(projects.core.logging)
            implementation(projects.core.datastore.api)
            implementation(libs.kotlinx.serialization.json)
        }
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
    systemProperty("java.awt.headless", "true")
    systemProperty("heartbeat.claude.liveReport", layout.buildDirectory.file("claude-live-report.txt").get().asFile.path)
    providers.gradleProperty("heartbeat.claude.executable").orNull?.let {
        systemProperty("heartbeat.claude.executable", it)
    }
    testLogging { events("passed", "failed", "skipped") }
}
