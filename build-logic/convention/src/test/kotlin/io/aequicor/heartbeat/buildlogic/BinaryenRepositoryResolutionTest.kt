package io.aequicor.heartbeat.buildlogic

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BinaryenRepositoryResolutionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `settings dependencies resolve before and after the Windows ARM64 Binaryen download`() {
        val projectDir = temporaryFolder.newFolder()
        val module = projectDir.resolve("maven/fixture/library/1.0").apply { mkdirs() }
        module.resolve("library-1.0.pom").writeText(
            """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>fixture</groupId><artifactId>library</artifactId><version>1.0</version>
            </project>
            """.trimIndent(),
        )
        module.resolve("library-1.0.jar").writeText("settings dependency")
        val archives = projectDir.resolve("binaryen/version_130").apply { mkdirs() }
        archives.resolve("binaryen-version_130-arm64-windows.tar.gz").writeText("Windows ARM64 archive")
        projectDir.resolve("settings.gradle").writeText(
            """
            rootProject.name = 'binaryen-repository-regression'
            dependencyResolutionManagement {
                repositories { maven { url = uri('maven') } }
            }
            """.trimIndent(),
        )
        projectDir.writeResolutionBuildFile()

        val runner = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("verifyResolution", "--offline", "--stacktrace", "--configuration-cache")
        val first = runner.build()
        val cached = runner.build()

        assertEquals(TaskOutcome.SUCCESS, first.task(":verifyResolution")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, cached.task(":verifyResolution")?.outcome)
        assertTrue(cached.output.contains("Configuration cache entry reused."))
    }

    private fun File.writeResolutionBuildFile() {
        resolve("build.gradle").writeText(
            """
            import io.aequicor.heartbeat.buildlogic.BinaryenWindowsArm64Kt
            import org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenPlugin
            import org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenEnvSpec
            import org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenSetupTask

            plugins { id 'heartbeat.kmp.wasm' apply false }
            BinaryenWindowsArm64Kt.configureBinaryenWindowsArm64(project, 'Windows 11', 'aarch64')
            apply plugin: BinaryenPlugin
            extensions.getByType(BinaryenEnvSpec).with {
                version.set('130')
                downloadBaseUrl.set(uri('binaryen').toString())
            }
            configurations { beforeDownload; afterDownload }
            dependencies {
                beforeDownload 'fixture:library:1.0'
                afterDownload 'fixture:library:1.0'
            }
            abstract class ResolutionProbe extends DefaultTask {
                @InputFile abstract RegularFileProperty getBeforeArchive()
                @InputFile abstract RegularFileProperty getBinaryenArchive()
                @InputFile abstract RegularFileProperty getAfterArchive()

                @TaskAction void verify() {
                    assert beforeArchive.get().asFile.text == 'settings dependency'
                    assert binaryenArchive.get().asFile.text == 'Windows ARM64 archive'
                    assert afterArchive.get().asFile.text == 'settings dependency'
                }
            }
            tasks.register('verifyResolution', ResolutionProbe) {
                beforeArchive.set(configurations.beforeDownload.singleFile)
                binaryenArchive.set(tasks.withType(BinaryenSetupTask).iterator().next().dist)
                afterArchive.set(configurations.afterDownload.singleFile)
                assert repositories.empty
            }
            """.trimIndent(),
        )
    }
}
