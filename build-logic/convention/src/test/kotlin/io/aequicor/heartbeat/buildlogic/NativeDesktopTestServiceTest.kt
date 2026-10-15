package io.aequicor.heartbeat.buildlogic

import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.gradle.api.tasks.testing.Test as GradleTest

class NativeDesktopTestServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `native tests from different projects share one exclusive desktop and a single worker`() {
        val root = ProjectBuilder.builder().withProjectDir(temporaryFolder.newFolder()).build()
        val nativeTests = listOf("studio", "research", "browser").map { name ->
            val project = ProjectBuilder.builder().withName(name).withParent(root).build()
            project.tasks.register("nativeTest", GradleTest::class.java).get().apply {
                maxParallelForks = 4
                useNativeDesktop()
            }
        }
        val desktop = root.gradle.sharedServices.registrations.getByName("heartbeatNativeDesktopTests")

        assertEquals(1, desktop.maxParallelUsages.get(), "The desktop must have one owner across projects")
        for (test in nativeTests) {
            assertEquals(1, test.maxParallelForks, test.path)
            assertSame(desktop.service.get(), test.requiredServices.elements.single().get(), test.path)
            assertTrue(test.taskDependencies.getDependencies(test).isEmpty(), test.path)
        }

        val ordinaryTest = root.tasks.register("ordinaryTest", GradleTest::class.java).get().apply {
            maxParallelForks = 4
        }
        assertTrue(ordinaryTest.requiredServices.elements.isEmpty())
        assertEquals(4, ordinaryTest.maxParallelForks, "Unrelated test workers keep their parallelism")
    }

    @Test
    fun `parallel native test tasks retain the desktop service with configuration cache reuse`() {
        val projectDir = temporaryFolder.newFolder()
        val modules = listOf("studio", "research", "browser")
        modules.forEach { projectDir.resolve(it).mkdirs() }
        projectDir.resolve("settings.gradle").writeText(
            """
            rootProject.name = 'native-desktop-test-regression'
            include 'studio', 'research', 'browser'
            """.trimIndent(),
        )
        projectDir.resolve("build.gradle").writeText(
            """
            import io.aequicor.heartbeat.buildlogic.NativeDesktopTestServiceKt

            plugins { id 'heartbeat.kmp.wasm' apply false }

            // Empty suites exercise Test task/service serialization without a test-framework dependency.
            subprojects {
                apply plugin: 'java'
                tasks.named('test', Test) {
                    maxParallelForks = 4
                    NativeDesktopTestServiceKt.useNativeDesktop(it)
                }
            }
            """.trimIndent(),
        )
        val runner = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(
                "test",
                "--parallel",
                "--max-workers=3",
                "--configuration-cache",
                "--offline",
                "--stacktrace",
            )

        val first = runner.build()
        val cached = runner.build()

        for (result in listOf(first, cached)) {
            for (module in modules) {
                assertEquals(TaskOutcome.NO_SOURCE, result.task(":$module:test")?.outcome, result.output)
            }
        }
        assertTrue(cached.output.contains("Configuration cache entry reused."), cached.output)
    }
}
