package io.aequicor.heartbeat.buildlogic

import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KmpWasmConventionPluginTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `allTests compiles Wasm tests without linking or validating a disabled browser runner`() {
        val projectDir = temporaryFolder.newFolder()
        projectDir.resolve("settings.gradle").writeText("rootProject.name = 'wasm-test-regression'")
        projectDir.resolve("gradle.properties").writeText("kotlin.stdlib.default.dependency=false")
        projectDir.resolve("src/commonTest/kotlin/Marker.kt").apply {
            parentFile.mkdirs()
            writeText("class Marker")
        }
        projectDir.resolve("build.gradle").writeText(
            """
            plugins { id 'heartbeat.kmp.wasm' }

            // This fixture only inspects the task graph. Configuration cache serializes compiler
            // classpaths even in dry-run mode, so remove external artifacts before resolution.
            // Task dependencies and compilation outputs remain intact without a repository or cache.
            configurations.configureEach {
                withDependencies { dependencies -> dependencies.clear() }
            }

            // Compose adds this prerequisite after the Wasm convention configures browser tests.
            afterEvaluate {
                def executableCheck = tasks.register('checkComposeUiTestConfigurationForWasmJs') {
                    doLast { throw new GradleException('No executable binary is declared') }
                }
                tasks.named('wasmJsBrowserTest').configure { dependsOn(executableCheck) }
                assert !executableCheck.get().enabled
                def browserTest = tasks.named('wasmJsBrowserTest').get()
                assert !browserTest.enabled
                assert !browserTest.inputFileProperty.present
            }
            """.trimIndent(),
        )

        val runner = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("allTests", "--dry-run", "--offline", "--stacktrace", "--configuration-cache")
        val first = runner.build()
        val cached = runner.build()

        for (result in listOf(first, cached)) {
            assertTrue(result.output.contains(":compileTestKotlinWasmJs SKIPPED"), result.output)
            assertFalse(result.output.contains("ExecutableKotlinWasmJs"), result.output)
            assertFalse(result.output.contains("ExecutableCompileSync"), result.output)
            assertFalse(result.output.contains(":kotlinWasmNodeJsSetup"), result.output)
            assertFalse(result.output.contains(":kotlinWasmNpmInstall"), result.output)
            assertFalse(result.output.contains(":kotlinWasmYarnSetup"), result.output)
        }
        assertTrue(cached.output.contains("Configuration cache entry reused."), cached.output)
    }
}
