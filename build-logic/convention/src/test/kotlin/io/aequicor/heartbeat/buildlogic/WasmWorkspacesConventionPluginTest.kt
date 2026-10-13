package io.aequicor.heartbeat.buildlogic

import groovy.json.JsonSlurper
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.targets.js.npm.tasks.RootPackageJsonTask
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals

class WasmWorkspacesConventionPluginTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `Windows workspace globs are normalized without changing other fields`() {
        val file = temporaryFolder.newFile("package.json")
        file.writeText(
            """
            {
              "private": true,
              "workspaces": ["packages\\heartbeat-panel", "packages_imported/datetime/0.8.0"],
              "dependencies": {"@js-joda/core": "3.2.0"},
              "scripts": {"start": "node tools\\start.js"},
              "resolutions": {"example": "1.2.3"}
            }
            """.trimIndent(),
        )
        val original = JsonSlurper().parse(file) as Map<*, *>

        normalizeYarnWorkspacePaths(file)

        assertEquals(
            original + ("workspaces" to listOf("packages/heartbeat-panel", "packages_imported/datetime/0.8.0")),
            JsonSlurper().parse(file),
        )
    }

    @Test
    fun `POSIX and non-workspace manifests are not rewritten`() {
        for (content in listOf("{\"workspaces\": [\"packages/panel\"]}\n", "{\"private\":true}\n")) {
            val file = temporaryFolder.newFile()
            file.writeText(content)

            normalizeYarnWorkspacePaths(file)

            assertEquals(content, file.readText())
        }
    }

    @Test
    fun `multiple Wasm modules attach only one normalization action to the root task`() {
        val project = ProjectBuilder.builder().withProjectDir(temporaryFolder.newFolder()).build()
        val task = project.tasks.register("wasmRootPackageJson", RootPackageJsonTask::class.java).get()
        val originalActionCount = task.actions.size
        val file = temporaryFolder.newFile("root-package.json")
        file.writeText("""{"workspaces":["packages\\panel"]}""")
        task.rootPackageJsonFile.set(project.layout.file(project.provider { file }))

        repeat(2) { project.pluginManager.apply(WasmWorkspacesConventionPlugin::class.java) }
        task.actions.last().execute(task)

        assertEquals(originalActionCount + 1, task.actions.size)
        assertEquals(listOf("packages/panel"), (JsonSlurper().parse(file) as Map<*, *>)["workspaces"])
    }
}
