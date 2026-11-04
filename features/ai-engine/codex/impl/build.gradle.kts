import io.aequicor.heartbeat.buildlogic.WriteCodexBuildContract

plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
}

// What this adapter was compiled from: content hashes of the facade/core contract and the Codex connector sources.
// No Git state, timestamps or absolute paths enter the generated constants.
val codexBuildContract = tasks.register<WriteCodexBuildContract>("generateCodexBuildContract") {
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
    packageName.set("io.aequicor.heartbeat.feature.aiengine.codex.impl.data.generated")
    outputDirectory.set(layout.buildDirectory.dir("generated/codexBuildContract"))
}

kotlin {
    sourceSets {
        jvmMain { kotlin.srcDir(codexBuildContract.flatMap { it.outputDirectory }) }
        commonMain.dependencies {
            implementation(projects.features.aiEngine.codex.api)
            implementation(projects.features.searchEngine.api)
            implementation(projects.features.questionnaire.api)
            implementation(projects.core.common)
            implementation(projects.core.di.api)
            implementation(projects.core.datastore.api)
            implementation(projects.core.logging)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.okio)
        }
    }
}
