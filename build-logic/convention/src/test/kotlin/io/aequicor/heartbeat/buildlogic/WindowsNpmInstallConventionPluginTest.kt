package io.aequicor.heartbeat.buildlogic

import org.gradle.kotlin.dsl.register
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.targets.js.npm.tasks.KotlinNpmInstallTask
import org.jetbrains.kotlin.gradle.targets.js.npm.tasks.KotlinToolingSetupTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowsNpmInstallConventionPluginTest {
    @Test
    fun `Windows installs application dependencies after tooling without requiring tooling`() {
        val project = ProjectBuilder.builder().build()
        project.configureWindowsNpmInstall("Windows 11")
        val install = project.tasks.register<KotlinNpmInstallTask>("kotlinWasmNpmInstall").get()
        val tooling = project.tasks.register<KotlinToolingSetupTask>("kotlinWasmToolingSetup").get()

        assertEquals(setOf(tooling), install.mustRunAfter.getDependencies(install))
        assertTrue(install.taskDependencies.getDependencies(install).isEmpty())
    }

    @Test
    fun `other hosts retain the original task ordering`() {
        for (os in listOf("Mac OS X", "Linux")) {
            val project = ProjectBuilder.builder().build()
            val install = project.tasks.register<KotlinNpmInstallTask>("kotlinWasmNpmInstall").get()
            project.tasks.register<KotlinToolingSetupTask>("kotlinWasmToolingSetup")
            project.configureWindowsNpmInstall(os)

            assertTrue(install.mustRunAfter.getDependencies(install).isEmpty(), os)
        }
    }
}
