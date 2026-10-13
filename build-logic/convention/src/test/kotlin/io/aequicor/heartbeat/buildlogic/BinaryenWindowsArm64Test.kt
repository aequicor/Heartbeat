package io.aequicor.heartbeat.buildlogic

import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenPlugin
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

@OptIn(ExperimentalWasmDsl::class)
class BinaryenWindowsArm64Test {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `Windows ARM64 resolves the published archive from the configured download URL`() {
        for (arch in listOf("aarch64", "arm64")) {
            val project = project()
            project.configureBinaryenWindowsArm64("Windows 11", arch)
            project.plugins.apply(BinaryenPlugin::class.java)
            val repository = temporaryFolder.newFolder()
            val archive = repository.resolve("version_130/binaryen-version_130-arm64-windows.tar.gz")
            archive.parentFile.mkdirs()
            archive.writeText("native Windows ARM64 archive")
            project.extensions.getByType<BinaryenEnvSpec>().downloadBaseUrl.set(repository.toURI().toString())

            assertEquals(archive, project.resolve("com.github.webassembly:binaryen:130:aarch64-windows@tar.gz"))
            assertFails { project.resolve("unrelated:binaryen:130:aarch64-windows@tar.gz") }
        }
    }

    @Test
    fun `other hosts retain Kotlin download repositories`() {
        for ((os, arch) in listOf(
            "Windows 11" to "amd64",
            "Mac OS X" to "aarch64",
            "Linux" to "aarch64",
        )) {
            val project = project()
            project.configureBinaryenWindowsArm64(os, arch)
            project.plugins.apply(BinaryenPlugin::class.java)

            assertTrue(project.repositories.isEmpty(), "$os $arch")
        }
    }

    @Test
    fun `an explicitly disabled download repository is respected`() {
        val project = project()
        project.plugins.apply(BinaryenPlugin::class.java)
        project.extensions.getByType<BinaryenEnvSpec>().downloadBaseUrl.set(null as String?)

        project.configureBinaryenWindowsArm64("Windows 11", "aarch64")

        assertTrue(project.repositories.isEmpty())
    }

    private fun project(): Project = ProjectBuilder.builder().withProjectDir(temporaryFolder.newFolder()).build()

    private fun Project.resolve(notation: String) =
        configurations.detachedConfiguration(dependencies.create(notation)).singleFile
}
