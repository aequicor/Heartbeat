plugins {
    alias(libs.plugins.heartbeat.kmp.library)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
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
