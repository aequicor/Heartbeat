package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class CodexExecutableTest {
    @Test
    fun `finder environment discovers native CLI inside ChatGPT bundle`() {
        assertEquals(CHATGPT_CLI, resolve(available = setOf(CHATGPT_CLI)))
    }

    @Test
    fun `PATH installation takes precedence over bundled CLI`() {
        val custom = "/custom/bin/codex"
        assertEquals(custom, resolve(path = "/custom/bin", available = setOf(custom, CHATGPT_CLI)))
    }

    @Test
    fun `explicit command is never replaced by discovered installation`() {
        assertEquals("/custom/codex", resolve(configured = "/custom/codex", available = setOf(CHATGPT_CLI)))
    }

    @Test
    fun `user application and homebrew installations work without shell PATH`() {
        val userCli = "/users/test/Applications/Codex.app/Contents/Resources/codex"
        assertEquals(userCli, resolve(available = setOf(userCli)))
        val homebrew = "/opt/homebrew/bin/codex"
        assertEquals(homebrew, resolve(available = setOf(homebrew)))
    }

    @Test
    fun `missing executable retains original command for normal failure reporting`() {
        assertEquals("codex", resolve(available = emptySet()))
    }

    @Test
    fun `non-mac platforms keep normal process resolution`() {
        assertEquals("codex", resolve(osName = "Windows 11", available = setOf(CHATGPT_CLI)))
        assertEquals("codex", resolve(osName = "Linux", available = setOf(CHATGPT_CLI)))
    }

    @Test
    fun `unavailable PATH command does not hide an executable app bundle`() {
        assertEquals(CHATGPT_CLI, resolve(path = "/not-executable/bin", available = setOf(CHATGPT_CLI)))
    }

    private fun resolve(
        configured: String = "codex",
        osName: String = "Mac OS X",
        path: String? = listOf("/usr/bin", "/bin").joinToString(File.pathSeparator),
        available: Set<String>,
    ): String = resolveCodexExecutable(configured, osName, path, "/users/test") {
        it.invariantSeparatorsPath in available
    }.replace(File.separatorChar, '/')

    private companion object {
        const val CHATGPT_CLI = "/Applications/ChatGPT.app/Contents/Resources/" +
            "codex-cli/CodexCLI.app/Contents/MacOS/codex"
    }
}
