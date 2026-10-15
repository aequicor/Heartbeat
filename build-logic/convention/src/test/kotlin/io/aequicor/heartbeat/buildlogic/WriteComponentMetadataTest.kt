package io.aequicor.heartbeat.buildlogic

import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WriteComponentMetadataTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `metadata pins archive bytes and stays identical across builds`() {
        val task = fixture()
        task.artifact.get().asFile.writeText("abc")

        task.write()
        val first = task.outputFile.get().asFile.readBytes()
        task.write()

        assertContentEquals(first, task.outputFile.get().asFile.readBytes())
        val metadata = Properties().apply { first.inputStream().use(::load) }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", metadata["sha256"])
        assertEquals("3", metadata["size"])
        assertEquals("https://example.test/v1.0.0/extension.zip", metadata["url"])
    }

    @Test
    fun `properties cannot inject unreviewed pins or insecure source`() {
        val task = fixture()
        val invalidUrls = listOf(
            "http://example.test/a",
            "https://example.test/a\nsha256=evil",
            "https://example.test/\\x",
        )
        for (invalid in invalidUrls) {
            task.downloadUrl.set(invalid)
            assertFailsWith<IllegalArgumentException> { task.write() }
        }
        task.downloadUrl.set("https://example.test/extension.zip")
        task.version.set("1.0.0\nsize=0")
        assertFailsWith<IllegalArgumentException> { task.write() }
    }

    private fun fixture(): WriteComponentMetadata {
        val project = ProjectBuilder.builder().withProjectDir(temporaryFolder.newFolder()).build()
        return project.tasks.create("metadata", WriteComponentMetadata::class.java).apply {
            artifact.set(project.layout.projectDirectory.file("extension.zip"))
            version.set("1.0.0")
            downloadUrl.set("https://example.test/v1.0.0/extension.zip")
            outputFile.set(project.layout.buildDirectory.file("component.properties"))
        }
    }
}
