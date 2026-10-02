package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowsCodexExecutableTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `desktop app CLI is discovered outside PATH in local app data`() {
        val cli = install("OpenAI/Codex/bin/be3fd7e5c1969ff6/codex.exe")

        assertEquals(cli.path, resolve())
    }

    @Test
    fun `native PATH installation takes precedence and supports spaces`() {
        val cli = install("custom tools/codex.exe")
        install("OpenAI/Codex/bin/desktop/codex.exe")

        assertEquals(cli.path, resolve(path = ";${File(temporaryFolder.root, "missing")};\"${cli.parent}\";"))
    }

    @Test
    fun `batch wrappers on PATH do not hide native desktop CLI`() {
        val wrapper = install("npm/codex.cmd")
        install("npm/codex.bat")
        val cli = install("OpenAI/Codex/bin/desktop/codex.exe")

        assertEquals(cli.path, resolve(path = wrapper.parent))
    }

    @Test
    fun `newest complete native installation is used after desktop updates`() {
        val older = install("OpenAI/Codex/bin/older/codex.exe")
        val newer = install("OpenAI/Codex/bin/newer/codex.exe")
        val otherTool = install("OpenAI/Codex/bin/other/rg.exe")
        assertTrue(older.parentFile.setLastModified(10_000))
        assertTrue(newer.parentFile.setLastModified(20_000))
        assertTrue(otherTool.parentFile.setLastModified(30_000))

        assertEquals(newer.path, resolve())
    }

    @Test
    fun `missing local app data falls back to user home`() {
        val cli = install("AppData/Local/OpenAI/Codex/bin/desktop/codex.exe")

        assertEquals(cli.path, resolve(localAppData = null))
        assertEquals(cli.path, resolve(localAppData = ""))
    }

    @Test
    fun `explicit executable is preserved even when desktop is installed`() {
        install("OpenAI/Codex/bin/desktop/codex.exe")
        val explicit = File(temporaryFolder.root, "custom/codex.exe").path

        assertEquals(explicit, resolve(configured = explicit))
    }

    @Test
    fun `absent or incomplete desktop install retains normal failure reporting`() {
        assertEquals("codex", resolve())
        install("OpenAI/Codex/bin/other/rg.exe")
        install("OpenAI/Codex/bin/partial/codex.exe/unfinished")

        assertEquals("codex", resolve())
    }

    private fun resolve(
        configured: String = "codex",
        path: String? = null,
        localAppData: String? = temporaryFolder.root.path,
    ): String = resolveCodexExecutable(
        configured = configured,
        osName = "Windows 11",
        path = path,
        userHome = temporaryFolder.root.path,
        localAppData = localAppData,
        isExecutable = File::isFile,
    )

    private fun install(relativePath: String): File = File(temporaryFolder.root, relativePath).apply {
        assertTrue(parentFile.isDirectory || parentFile.mkdirs())
        writeText("")
    }
}
