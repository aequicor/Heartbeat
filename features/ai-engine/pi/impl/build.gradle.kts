import io.aequicor.heartbeat.buildlogic.WritePiBuildContract

plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
}

// What this adapter was compiled from: the pinned Pi distribution plus content hashes of the facade/core contract
// and the Pi connector sources. No Git state, timestamps or absolute paths enter the generated constants.
val piBuildContract = tasks.register<WritePiBuildContract>("generatePiBuildContract") {
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
    runtimeVersion.set(libs.versions.pi)
    packageName.set("io.aequicor.heartbeat.feature.aiengine.pi.impl.data.generated")
    outputDirectory.set(layout.buildDirectory.dir("generated/piBuildContract"))
}

kotlin {
    sourceSets {
        jvmMain { kotlin.srcDir(piBuildContract.flatMap { it.outputDirectory }) }
        commonMain.dependencies {
            implementation(projects.features.aiEngine.pi.api)
            implementation(projects.features.searchEngine.api)
            implementation(projects.core.di.api)
            implementation(projects.core.common)
            implementation(projects.core.logging)
            implementation(projects.core.datastore.api)
            implementation(projects.core.secrets.api)
            implementation(projects.core.network.api)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
        }
        jvmMain.dependencies {
            implementation(libs.apache.commons.compress)
        }
    }
}

// Opt-in real Node/PowerShell contracts; not part of check and does not contact a provider.
tasks.register<Exec>("powerShellProcessTest") {
    group = "verification"
    description = "Runs controlled PowerShell Node contracts with Node 22.18+; process cases require Windows"
    workingDir(layout.projectDirectory.asFile)
    commandLine(
        "node",
        "--test",
        layout.projectDirectory.file("src/jvmTest/resources/pi/heartbeat-approval.test.mjs").asFile.absolutePath,
        layout.projectDirectory.file("src/jvmTest/resources/pi/heartbeat-powershell.test.mjs").asFile.absolutePath,
    )
}
