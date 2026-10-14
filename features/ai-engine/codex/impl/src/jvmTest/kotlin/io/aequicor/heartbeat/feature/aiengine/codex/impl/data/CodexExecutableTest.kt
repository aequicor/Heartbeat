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
    fun `Linux never discovers macOS app bundles`() {
        assertEquals("codex", resolve(osName = "Linux", available = setOf(CHATGPT_CLI)))
    }

    @Test
    fun `Linux resolves PATH first and local bin without a shell environment`() {
        val local = "/users/test/.local/bin/codex"
        val system = "/usr/local/bin/codex"
        val custom = "/custom/bin/codex"
        assertEquals(
            custom,
            resolve(osName = "Linux", path = "/custom/bin", available = setOf(custom, local, system)),
        )
        assertEquals(local, resolve(osName = "Linux", path = null, available = setOf(local, system)))
        assertEquals(system, resolve(osName = "Linux", path = null, available = setOf(system)))
        assertEquals("codex", resolve(osName = "FreeBSD", path = "/custom/bin", available = setOf(custom)))
    }

    @Test
    fun `unavailable PATH command does not hide an executable app bundle`() {
        assertEquals(CHATGPT_CLI, resolve(path = "/not-executable/bin", available = setOf(CHATGPT_CLI)))
    }

    private fun resolve(
        configured: String = "codex",
        osName: String = "Mac OS X",
        path: String? = "/usr/bin:/bin",
        available: Set<String>,
    ): String = resolveCodexExecutable(configured, osName, path, "/users/test", localAppData = null) {
        it.invariantSeparatorsPath in available
    }.replace(File.separatorChar, '/')

    private companion object {
        const val CHATGPT_CLI = "/Applications/ChatGPT.app/Contents/Resources/" +
            "codex-cli/CodexCLI.app/Contents/MacOS/codex"
    }
}
