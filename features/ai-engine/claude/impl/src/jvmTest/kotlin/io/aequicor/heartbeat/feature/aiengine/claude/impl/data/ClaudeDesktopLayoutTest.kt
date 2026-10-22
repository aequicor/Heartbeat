package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.HostAppState
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostRuntime
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClaudeDesktopLayoutTest {
    private val macRoot = "/Users/test/Library/Application Support/Claude/claude-code"

    @Test
    fun `the installed macOS app reports its version and every runtime newest first`() {
        val host = testClaudeHost(
            directories = mapOf(
                "/Applications/Claude.app" to listOf("Contents"),
                macRoot to listOf("2.1.9", "2.1.304", "notes"),
                "$macRoot/2.1.304" to listOf("b2", "b1"),
                "$macRoot/2.1.304/b2" to listOf("claude.app"),
                "$macRoot/2.1.304/b1" to listOf(".verified", "claude.app"),
                "$macRoot/2.1.9" to listOf("a1"),
                "$macRoot/2.1.9/a1" to listOf(".verified", "claude.app"),
            ),
            available = setOf(macExe("2.1.304", "b2"), macExe("2.1.304", "b1"), macExe("2.1.9", "a1")),
            files = mapOf("/Applications/Claude.app/Contents/Info.plist" to plist("3.1623.1")),
        )

        val state = inspectClaudeDesktop(host, configDirectory = null)

        assertEquals(
            HostAppState(
                isInstalled = true,
                version = "3.1623.1",
                path = File("/Applications/Claude.app").path,
                runtimes = listOf(
                    HostRuntime("2.1.304", "b2", isVerified = false, path = macExe("2.1.304", "b2")),
                    HostRuntime("2.1.304", "b1", isVerified = true, path = macExe("2.1.304", "b1")),
                    HostRuntime("2.1.9", "a1", isVerified = true, path = macExe("2.1.9", "a1")),
                ),
                configDirectory = File("/Users/test", ".claude").path,
            ),
            state,
        )
        assertEquals("b1", state.newest?.build)
        assertEquals(macExe("2.1.9", "a1"), claudeDesktopRuntime(host, "2.1.9")?.path)
        assertNull(claudeDesktopRuntime(host, "2.1.300"))
        assertEquals(
            listOf("/usr/bin/open", "-a", File("/Applications/Claude.app").path),
            claudeDesktopOpenCommand(host),
        )
    }

    @Test
    fun `runtimes left behind by an uninstalled app are listed but the app cannot be opened`() {
        val host = testClaudeHost(
            directories = mapOf(
                macRoot to listOf("2.1.1"),
                "$macRoot/2.1.1" to listOf("a"),
                "$macRoot/2.1.1/a" to listOf(".verified"),
            ),
            available = setOf(macExe("2.1.1", "a")),
        )

        val state = inspectClaudeDesktop(host, configDirectory = "/work/claude")

        assertEquals(false, state.isInstalled)
        assertNull(state.version)
        assertEquals(1, state.runtimes.size)
        assertEquals("/work/claude", state.configDirectory)
        assertNull(claudeDesktopOpenCommand(host))
    }

    @Test
    fun `windows reads the per-user installer and the runtimes in roaming app data`() {
        val local = "C:/Users/test/AppData/Local"
        val roaming = "C:/Users/test/AppData/Roaming"
        val exe = File("$roaming/Claude/claude-code/2.1.304/b1", "claude.exe").path
        val host = testClaudeHost(
            osName = "Windows 11",
            environment = mapOf("LOCALAPPDATA" to File(local).path, "APPDATA" to File(roaming).path),
            directories = mapOf(
                "$local/AnthropicClaude" to listOf("app-0.9.3", "app-0.10.1", "packages"),
                "$roaming/Claude/claude-code" to listOf("2.1.304"),
                "$roaming/Claude/claude-code/2.1.304" to listOf("b1"),
                "$roaming/Claude/claude-code/2.1.304/b1" to listOf(".verified", "claude.exe"),
            ),
            available = setOf(exe, "$local/AnthropicClaude/claude.exe"),
        )

        val state = inspectClaudeDesktop(host, configDirectory = null)

        assertEquals(true, state.isInstalled)
        assertEquals("0.10.1", state.version)
        assertEquals(exe, state.newest?.path)
        assertEquals(listOf(File("$local/AnthropicClaude/claude.exe").path), claudeDesktopOpenCommand(host))
    }

    @Test
    fun `Linux has no Claude desktop app`() {
        val host = testClaudeHost(osName = "Linux")
        assertEquals(HostAppState(), inspectClaudeDesktop(host, configDirectory = null))
        assertNull(claudeDesktopOpenCommand(host))
    }

    private fun macExe(version: String, build: String) =
        File("$macRoot/$version/$build/claude.app/Contents/MacOS/claude").path

    private fun plist(version: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <plist version="1.0"><dict>
            <key>CFBundleIdentifier</key>
            <string>com.anthropic.claudefordesktop</string>
            <key>CFBundleShortVersionString</key>
            <string>$version</string>
        </dict></plist>
        """.trimIndent()
}
