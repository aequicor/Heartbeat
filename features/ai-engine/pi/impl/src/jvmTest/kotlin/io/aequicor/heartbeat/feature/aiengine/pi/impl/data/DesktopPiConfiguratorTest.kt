package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigFailure
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopPiConfiguratorTest {
    private val root = Files.createTempDirectory("pi-config")
    private val outside = Files.createTempDirectory("pi-outside")
    private val configurator = DesktopPiConfigurator({ root.resolve("home") })

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
        outside.toFile().deleteRecursively()
    }

    @Test
    fun `an empty project has no documents`() = runTest {
        assertEquals(emptyMap(), configurator.readProject(root.toString()).documents)
        assertFalse(configurator.hasGatedConfiguration(root.toString()))
    }

    @Test
    fun `documents are written where Pi reads them`() = runTest {
        val written = configurator.writeProject(
            root.toString(),
            mapOf(PiDocuments.Settings to "{}\n", PiDocuments.Agents to "Be brief"),
            mapOf(PiDocuments.Settings to null, PiDocuments.Agents to null),
        )

        assertEquals("{}\n", root.resolve(".pi/settings.json").readText())
        assertEquals("Be brief", root.resolve("AGENTS.md").readText())
        assertEquals(setOf(PiDocuments.Settings, PiDocuments.Agents), written.documents.keys)
        assertEquals(revisionOf("Be brief".toByteArray()), written.documents.getValue(PiDocuments.Agents).revision)
        assertTrue(configurator.hasGatedConfiguration(root.toString()))
        assertEquals(
            listOf("settings.json"),
            Files.list(root.resolve(".pi")).use { it.map { p -> p.fileName.toString() }.toList() },
        )
    }

    @Test
    fun `a file changed since it was read is not overwritten`() = runTest {
        root.resolve("AGENTS.md").writeText("Theirs")

        val error = assertFailsWith<ConfigException> {
            configurator.writeProject(
                root.toString(),
                mapOf(PiDocuments.Agents to "Mine"),
                mapOf(PiDocuments.Agents to null),
            )
        }

        assertEquals(ConfigFailure.Conflict, error.failure)
        assertEquals("Theirs", root.resolve("AGENTS.md").readText())
    }

    @Test
    fun `a document is deleted with null`() = runTest {
        root.resolve(".pi").createDirectories()
        root.resolve(".pi/SYSTEM.md").writeText("Replace")
        val revision = configurator.readProject(root.toString()).documents.getValue(PiDocuments.System).revision

        val written = configurator.writeProject(
            root.toString(),
            mapOf(PiDocuments.System to null),
            mapOf(PiDocuments.System to revision),
        )

        assertNull(written.documents[PiDocuments.System])
        assertFalse(Files.exists(root.resolve(".pi/SYSTEM.md")))
    }

    @Test
    fun `a linked file is written through its link`() = runTest {
        val target = root.resolve("CLAUDE.md").apply { writeText("Shared") }
        piTestSymbolicLink(root.resolve("AGENTS.md"), target.fileName)
        val revision = configurator.readProject(root.toString()).documents.getValue(PiDocuments.Agents).revision

        configurator.writeProject(
            root.toString(),
            mapOf(PiDocuments.Agents to "Updated"),
            mapOf(PiDocuments.Agents to revision),
        )

        assertTrue(Files.isSymbolicLink(root.resolve("AGENTS.md")))
        assertEquals("Updated", target.readText())
    }

    @Test
    fun `a file or folder linking out of the project is neither read nor written`() = runTest {
        val private = outside.resolve("profile").apply { writeText("export TOKEN=secret") }
        piTestSymbolicLink(root.resolve("AGENTS.md"), private)

        assertEquals(ConfigFailure.Storage, refused { configurator.readProject(root.toString()) })
        Files.delete(root.resolve("AGENTS.md"))
        piTestDirectoryLink(root.resolve(".pi"), outside)
        val write = mapOf(PiDocuments.Settings to "{}")
        assertEquals(ConfigFailure.Storage, refused { configurator.writeProject(root.toString(), write, emptyMap()) })
        assertEquals("export TOKEN=secret", private.readText())
        assertFalse(Files.exists(outside.resolve("settings.json")))
    }

    @Test
    fun `a directory link cannot write settings outside the project`() = runTest {
        piTestDirectoryLink(root.resolve(".pi"), outside)
        val write = mapOf(PiDocuments.Settings to "{}")
        assertEquals(ConfigFailure.Storage, refused { configurator.writeProject(root.toString(), write, emptyMap()) })
        assertFalse(Files.exists(outside.resolve("settings.json")))
    }

    @Test
    fun `a file that is not UTF-8 is not shown`() = runTest {
        Files.write(root.resolve("AGENTS.md"), byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41))

        assertEquals(ConfigFailure.Storage, refused { configurator.readProject(root.toString()) })
    }

    private suspend fun refused(block: suspend () -> Unit): ConfigFailure =
        assertFailsWith<ConfigException> { block() }.failure
}
