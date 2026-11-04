import io.aequicor.heartbeat.buildlogic.WriteKimiBuildContract

plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
}

// What this adapter was compiled from: content hashes of the facade/core contract and the Kimi connector
// (including the shared ACP client), never a version read from a binary. No Git state, timestamps or absolute
// paths enter the generated constants.
val kimiBuildContract = tasks.register<WriteKimiBuildContract>("generateKimiBuildContract") {
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
        fileTree(projectDir.parentFile.resolve("../acp-interface/api")) {
            include("src/*Main/kotlin/**", "build.gradle.kts")
            exclude("**/build/**")
        },
        fileTree(projectDir.parentFile.resolve("../acp-interface/impl")) {
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
    packageName.set("io.aequicor.heartbeat.feature.aiengine.kimi.impl.data.generated")
    outputDirectory.set(layout.buildDirectory.dir("generated/kimiBuildContract"))
}

kotlin {
    sourceSets.androidMain { kotlin.srcDir("src/mobileMain/kotlin") }
    sourceSets.iosMain { kotlin.srcDir("src/mobileMain/kotlin") }
    sourceSets.jvmMain { kotlin.srcDir(kimiBuildContract.flatMap { it.outputDirectory }) }
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
