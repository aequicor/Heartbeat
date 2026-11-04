import io.aequicor.heartbeat.buildlogic.WriteDshBuildContract

plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
}

// What this adapter was compiled from: content hashes of the facade/core contract and the DSH connector
// (including the shared ACP client). DSH is external, so no runtime version or artifact pin enters these facts.
// No Git state, timestamps or absolute paths enter the generated constants.
val dshBuildContract = tasks.register<WriteDshBuildContract>("generateDshBuildContract") {
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
    packageName.set("io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data.generated")
    outputDirectory.set(layout.buildDirectory.dir("generated/dshBuildContract"))
}

kotlin {
    sourceSets.androidMain { kotlin.srcDir("src/mobileMain/kotlin") }
    sourceSets.iosMain { kotlin.srcDir("src/mobileMain/kotlin") }
    sourceSets.jvmMain { kotlin.srcDir(dshBuildContract.flatMap { it.outputDirectory }) }
    sourceSets.commonMain.dependencies {
        implementation(projects.features.aiEngine.deepseekHarness.api)
        implementation(projects.features.aiEngine.acpInterface.api)
        implementation(projects.core.di.api)
        implementation(projects.core.common)
        implementation(projects.core.featureToggles.api)
        implementation(projects.core.logging)
        implementation(libs.kotlinx.serialization.json)
    }
    sourceSets.jvmMain.dependencies {
        implementation(libs.snakeyaml)
        implementation(libs.zstd.jni)
    }
}
