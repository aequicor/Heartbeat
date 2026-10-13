package io.aequicor.heartbeat.feature.appupdate.impl.data

import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalPathApi::class)
class HelperRollbackTest {
    private val root = createTempDirectory("app-update-rollback")
    private val installed = fakeBundle(Files.createDirectories(root.resolve("Applications")), "1.3.0")
    private val staged = fakeBundle(Files.createDirectories(root.resolve("staging")), "1.4.0", "staged.app")
    private val backup = installed.resolveSibling("Heartbeat.app.previous")
    private val stubs = Files.createDirectories(root.resolve("stubs"))
    private val opened = root.resolve("opened")

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `a partial cross-volume copy leaves the installed bundle untouched`() {
        if (!isMacHost) return
        stub(
            "mv",
            """
            case "$1" in
                */staged.app) mkdir "$2"; touch "$2/partial"; exit 1 ;;
            esac
            exec /bin/mv "$@"
            """,
        )

        assertEquals(1, run())
        assertEquals("1.3.0", bundleVersion(installed))
        assertEquals("$installed", opened.readText().trim())
        assertFalse(backup.exists())
        assertEquals(emptyList(), installed.parent.listDirectoryEntries("*.update.*"))
    }

    @Test
    fun `a partial destination is removed before restoring the backup`() {
        if (!isMacHost) return
        failActivation()

        assertEquals(1, run())
        assertEquals("1.3.0", bundleVersion(installed))
        assertEquals("$installed", opened.readText().trim())
        assertFalse(installed.resolve("partial").exists())
        assertFalse(installed.resolve("Heartbeat.app.previous").exists())
        assertFalse(backup.exists())
    }

    @Test
    fun `a failed partial destination cleanup preserves the last working backup`() {
        if (!isMacHost) return
        failActivation()
        stub(
            "rm",
            """
            case "$2" in */Applications/Heartbeat.app) exit 1 ;; esac
            exec /bin/rm "$@"
            """,
        )

        assertEquals(1, run())
        assertEquals("1.3.0", bundleVersion(backup))
        assertFalse(opened.exists())
    }

    @Test
    fun `a failed restoration preserves the last working backup`() {
        if (!isMacHost) return
        failActivation(failRestore = true)

        assertEquals(1, run())
        assertEquals("1.3.0", bundleVersion(backup))
        assertFalse(installed.exists())
        assertFalse(opened.exists())
    }

    private fun failActivation(failRestore: Boolean = false) {
        val restoreCase = if (failRestore) "*.previous) exit 1 ;;" else ""
        stub(
            "mv",
            """
            case "$1" in
                */prepared.app) mkdir "$2"; touch "$2/partial"; exit 1 ;;
                $restoreCase
            esac
            exec /bin/mv "$@"
            """,
        )
    }

    private fun stub(name: String, body: String) {
        stubs.resolve(name).apply {
            writeText("#!/bin/sh\n" + body.trimIndent() + "\n")
            toFile().setExecutable(true)
        }
    }

    private fun run(): Int {
        val script = root.resolve("apply-macos.sh")
        javaClass.getResourceAsStream("/app-update/apply-macos.sh")!!.use { Files.copy(it, script) }
        return runHelper(listOf("/bin/sh", "$script", "${endedProcessId()}", "$installed", "$staged"), stubs, opened)
    }
}
