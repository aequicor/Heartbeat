package io.aequicor.heartbeat.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.targets.js.npm.tasks.KotlinNpmInstallTask
import org.jetbrains.kotlin.gradle.targets.js.npm.tasks.KotlinToolingSetupTask

/**
 * Serialize Kotlin's tooling and application npm installations on Windows. Concurrent Yarn 1.22.22
 * invocations with Node 26 can finish without creating the application's node_modules directory.
 * Ordering does not add tooling installation to builds that only need application dependencies.
 */
internal class WindowsNpmInstallConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.configureWindowsNpmInstall(target.providers.systemProperty("os.name").get())
    }
}

internal fun Project.configureWindowsNpmInstall(osName: String) {
    if (!osName.contains("windows", ignoreCase = true)) return

    val toolingSetup = tasks.withType<KotlinToolingSetupTask>()
    tasks.withType<KotlinNpmInstallTask>().configureEach {
        mustRunAfter(toolingSetup)
    }
}
