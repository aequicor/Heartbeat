package io.aequicor.heartbeat.buildlogic

import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WriteEmbeddedEngineContractTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `relocation and collection order do not change generated facts`() {
        val a = fixture()
        val b = fixture()
        b.facadeSources.setFrom(b.sourceRoot.file("host/B.kt"), b.sourceRoot.file("host/A.kt"))
        a.write()
        b.write()
        assertEquals(output(a), output(b))
        assertFalse(output(a).contains(a.sourceRoot.get().asFile.absolutePath))
        assertFalse(output(a).contains(b.sourceRoot.get().asFile.absolutePath))
        a.write()
        assertEquals(output(a), output(b))
    }

    @Test
    fun `dirty source edits invalidate their own contract without requiring a git commit`() {
        val task = fixture()
        task.write()
        val initial = output(task)
        task.sourceRoot.file("host/A.kt").get().asFile.writeText("changed host")
        task.write()
        assertNotEquals(field(initial, "compiledFacade"), field(output(task), "compiledFacade"))
        assertEquals(field(initial, "connector"), field(output(task), "connector"))
        val beforeConnector = output(task)
        task.sourceRoot.file("connector/Engine.kt").get().asFile.writeText("changed connector")
        task.write()
        assertNotEquals(field(beforeConnector, "connector"), field(output(task), "connector"))
        assertEquals(field(beforeConnector, "compiledFacade"), field(output(task), "compiledFacade"))
    }

    @Test
    fun `same dependency coordinate with changed artifact bytes changes the sdk fingerprint`() {
        val task = fixture()
        task.write()
        val before = output(task)
        task.sdkArtifacts.singleFile.writeText("other actual SDK bytes")
        task.write()
        assertNotEquals(field(before, "dependencies"), field(output(task), "dependencies"))
        assertTrue(output(task).contains("ai.koog:client-jvm:1.3.0"))
        assertEquals(field(before, "connector"), field(output(task), "connector"))
    }

    @Test
    fun `selected variant participates in dependency identity`() {
        val task = fixture()
        task.write()
        val before = output(task)
        task.variants.set(mapOf("sdk.jar" to "another-selected-variant"))
        task.write()
        assertNotEquals(field(before, "dependencies"), field(output(task), "dependencies"))
    }

    @Test
    fun `missing artifacts or mismatched selected runtime version never emit a contract`() {
        val task = fixture()
        task.coordinates.set(mapOf("sdk.jar" to "ai.koog:client-jvm:1.4.0"))
        assertFailsWith<IllegalArgumentException> { task.write() }
        assertFalse(outputFile(task).exists())
        task.coordinates.set(emptyMap())
        assertFailsWith<IllegalArgumentException> { task.write() }
        assertFalse(outputFile(task).exists())
    }

    @Test
    fun `generation rejects ambiguous artifact names and invalid package input`() {
        val task = fixture()
        val duplicate = task.sourceRoot.file("other/sdk.jar").get().asFile
        duplicate.parentFile.mkdirs()
        duplicate.writeText("different artifact")
        task.sdkArtifacts.from(duplicate)
        assertFailsWith<IllegalArgumentException> { task.write() }
        task.packageName.set("not a package")
        assertFailsWith<IllegalArgumentException> { task.write() }
    }

    private fun fixture(): WriteEmbeddedEngineContract {
        val root = temporaryFolder.newFolder()
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        fun file(name: String, value: String): File = File(root, name).apply {
            parentFile.mkdirs()
            writeText(value)
        }
        return project.tasks.create("contract", WriteEmbeddedEngineContract::class.java).apply {
            sourceRoot.set(project.layout.projectDirectory)
            facadeSources.from(file("host/A.kt", "host A"), file("host/B.kt", "host B"))
            connectorSources.from(file("connector/Engine.kt", "engine source"))
            sdkArtifacts.from(file("sdk.jar", "actual SDK bytes"))
            coordinates.set(mapOf("sdk.jar" to "ai.koog:client-jvm:1.3.0"))
            variants.set(mapOf("sdk.jar" to "jvmRuntimeElements"))
            runtimeGroup.set("ai.koog")
            runtimeVersion.set("1.3.0")
            packageName.set("io.test.generated")
            outputDirectory.set(project.layout.buildDirectory.dir("generated"))
        }
    }

    private fun outputFile(task: WriteEmbeddedEngineContract): File =
        task.outputDirectory.file("io/test/generated/EmbeddedEngineBuild.kt").get().asFile

    private fun output(task: WriteEmbeddedEngineContract): String = outputFile(task).readText()

    private fun field(output: String, name: String): String =
        output.lineSequence().single { it.contains("const val $name:") }
}
