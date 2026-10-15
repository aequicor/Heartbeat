package io.aequicor.heartbeat.feature.projectconfig.impl.data

import io.aequicor.heartbeat.feature.projectconfig.api.ConfigFile
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigLayout
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigLimits
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigPath
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigProblemCode
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigRead
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigReadFailure
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigTree
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigWriteException
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigWriteFailure
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopConfigFilesTest {
    private val root: Path = Files.createTempDirectory("project-config")

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `a project without the folder has no configuration`() {
        assertEquals(ConfigRead.Absent, readProjectFolder(root))
        assertNull(projectFolderFingerprint(root))
    }

    @Test
    fun `written files read back with a manifest and without hidden or temporary files`() {
        writeProjectFolder(
            root,
            listOf(file("harnesses/review/harness.json", "{}"), file("learning/a.md", "x")),
            emptyList(),
        )
        root.resolve(".heartbeat/learning/.DS_Store").writeText("junk")
        root.resolve(".heartbeat/.git").createDirectories().resolve("config").writeText("junk")

        val tree = assertIs<ConfigRead.Read>(readProjectFolder(root)).tree

        assertEquals(listOf("harnesses/review/harness.json", "learning/a.md"), tree.files.map { it.path.value })
        assertTrue(root.resolve(".heartbeat/manifest.json").readText().contains(ConfigLayout.FORMAT))
        assertTrue(tree.ignored.isEmpty())
    }

    @Test
    fun `texts are normalized to unix line ends without a byte order mark`() {
        root.resolve(".heartbeat/learning").createDirectories().resolve("a.md").writeText("﻿one\r\ntwo\r\n")

        val tree = assertIs<ConfigRead.Read>(readProjectFolder(root)).tree

        assertEquals("one\ntwo\n", tree.files.single().text)
    }

    @Test
    fun `unsupported, oversized and non-text files are reported and skipped`() {
        val folder = root.resolve(".heartbeat").createDirectories()
        folder.resolve("bad name.md").writeText("x")
        folder.resolve("binary.bin").writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
        folder.resolve("big.md").writeBytes(ByteArray(ConfigLimits.FILE_BYTES + 1) { 'a'.code.toByte() })

        val tree = assertIs<ConfigRead.Read>(readProjectFolder(root)).tree

        assertTrue(tree.files.isEmpty())
        assertEquals(
            setOf(ConfigProblemCode.Unsupported, ConfigProblemCode.Syntax, ConfigProblemCode.Limit),
            tree.ignored.map { it.code }.toSet(),
        )
    }

    @Test
    fun `a newer manifest refuses the whole folder`() {
        root.resolve(".heartbeat").createDirectories().resolve("manifest.json")
            .writeText("""{"format":"heartbeat-config","version":${ConfigLayout.VERSION + 1}}""")

        assertEquals(ConfigRead.Failed(ConfigReadFailure.Version), readProjectFolder(root))
    }

    @Test
    fun `symbolic links are never followed`() {
        val outside = Files.createTempDirectory("outside").also { it.resolve("secret.md").writeText("secret") }
        try {
            Files.createSymbolicLink(root.resolve(".heartbeat"), outside)
            assertEquals(ConfigRead.Failed(ConfigReadFailure.NotDirectory), readProjectFolder(root))
            assertFailsWith<ConfigWriteException> {
                writeProjectFolder(
                    root,
                    listOf(file("learning/a.md", "x")),
                    emptyList(),
                )
            }
            assertFalse(outside.resolve("manifest.json").exists())

            Files.delete(root.resolve(".heartbeat"))
            val folder = root.resolve(".heartbeat").createDirectories()
            Files.createSymbolicLink(folder.resolve("learning"), outside)
            val tree = assertIs<ConfigRead.Read>(readProjectFolder(root)).tree
            assertTrue(tree.files.isEmpty())
            assertEquals(ConfigProblemCode.Unsupported, tree.ignored.single().code)
            val failure = assertFailsWith<ConfigWriteException> {
                writeProjectFolder(root, listOf(file("learning/a.md", "x")), emptyList())
            }
            assertEquals(ConfigWriteFailure.NotDirectory, failure.reason)
            assertFailsWith<ConfigWriteException> {
                writeProjectFolder(root, emptyList(), listOf(ConfigPath("learning/secret.md")))
            }
            assertTrue(outside.resolve("secret.md").exists())
        } finally {
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun `deleting the last file of a directory removes the directory and changes the fingerprint`() {
        writeProjectFolder(root, listOf(file("harnesses/review/harness.json", "{}")), emptyList())
        val before = projectFolderFingerprint(root)

        writeProjectFolder(root, emptyList(), listOf(ConfigPath("harnesses/review/harness.json")))

        assertFalse(root.resolve(".heartbeat/harnesses").exists())
        assertTrue(root.resolve(".heartbeat").exists())
        assertNotEquals(before, projectFolderFingerprint(root))
    }

    @Test
    fun `a bundle round-trips its tree and is reproducible`() {
        val tree = ConfigTree.of(listOf(file("learning/b.md", "b"), file("harnesses/review/harness.json", "{}")))
        val first = root.resolve("one.hbai")
        val second = root.resolve("two.hbai")

        writeConfigBundle(first, tree)
        writeConfigBundle(second, tree)

        assertEquals(tree, assertIs<ConfigRead.Read>(readConfigBundle(first)).tree)
        assertTrue(Files.readAllBytes(first).contentEquals(Files.readAllBytes(second)))
    }

    @Test
    fun `bundles without a manifest or with entries outside the tree are refused`() {
        val manifest = """{"format":"heartbeat-config","version":1}"""
        assertEquals(format, readConfigBundle(zip("none.hbai", "learning/a.md" to "x")))
        assertEquals(format, readConfigBundle(zip("abs.hbai", "manifest.json" to manifest, "/etc/passwd" to "x")))
        assertEquals(format, readConfigBundle(root.resolve("plain.hbai").also { it.writeText("not a zip") }))

        val skipped = readConfigBundle(
            zip("meta.hbai", "manifest.json" to manifest, "__MACOSX/a.md" to "x", "../escape.md" to "x", "a.md" to "y"),
        )
        assertEquals(listOf("a.md"), assertIs<ConfigRead.Read>(skipped).tree.files.map { it.path.value })
    }

    @Test
    fun `bundle entries are bounded by what they inflate to`() {
        val manifest = """{"format":"heartbeat-config","version":1}"""
        val bomb = zip("bomb.hbai", "manifest.json" to manifest, "a.md" to "a".repeat(ConfigLimits.FILE_BYTES + 1))

        assertEquals(ConfigRead.Failed(ConfigReadFailure.Limit), readConfigBundle(bomb))
    }

    private val format = ConfigRead.Failed(ConfigReadFailure.Format)

    private fun zip(name: String, vararg entries: Pair<String, String>): Path {
        val target = root.resolve(name)
        ZipOutputStream(Files.newOutputStream(target)).use { zip ->
            entries.forEach { (path, text) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.encodeToByteArray())
                zip.closeEntry()
            }
        }
        return target
    }

    private fun file(path: String, text: String) = ConfigFile(ConfigPath(path), text)
}
