package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EnvironmentEntry
import io.aequicor.heartbeat.feature.aiengine.facade.api.HOSTED_SEARCH_SERVER
import io.aequicor.heartbeat.feature.aiengine.facade.api.HOSTED_TOOLS_SERVER
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class ClaudeNativeMcpTest {
    private val workspace = Path.of(System.getProperty("java.io.tmpdir")).resolve("mcp-project").toAbsolutePath()
    private val home = workspace.resolve("user")
    private val enabled = ClaudeStartup("claude", InstallSource.Custom, true, isMcpEnabled = true)

    @Test
    fun `default off never reads native configuration`() = runTest {
        assertTrue(
            claudeNativeMcp(enabled.copy(isMcpEnabled = false), workspace, home) {
                error("Native configuration must not be read")
            }.isEmpty(),
        )
    }

    @Test
    fun `selected scopes merge with local precedence excluding disabled and hosted names`() = runTest {
        val global = buildJsonObject {
            put("mcpServers", Json.parseToJsonElement("""{"shared":{"command":"global"},"disabled":{"command":"x"}}"""))
            put(
                "projects",
                buildJsonObject {
                    put(
                        workspace.toString().replace('\\', '/'),
                        buildJsonObject {
                            put("disabledMcpServers", Json.parseToJsonElement("""["disabled"]"""))
                            put(
                                "mcpServers",
                                buildJsonObject {
                                    put(
                                        "shared",
                                        Json.parseToJsonElement("""{"command":"local","env":{"SECRET":"private"}}"""),
                                    )
                                    put(HOSTED_TOOLS_SERVER, buildJsonObject { put("command", "spoof") })
                                },
                            )
                        },
                    )
                    put(
                        workspace.resolve("other").toString(),
                        buildJsonObject {
                            put("mcpServers", buildJsonObject { put("foreign", buildJsonObject {}) })
                        },
                    )
                },
            )
        }
        val paths = mutableListOf<Path>()
        val result = claudeNativeMcp(enabled, workspace, home) {
            paths.add(it)
            if (it.fileName.toString() == ".claude.json") global else null
        }
        assertEquals(setOf("shared"), result.keys)
        assertTrue(result.toString().contains("private"))
        assertTrue(result.toString().contains("local"))
        assertFalse(result.toString().contains("global"))
        assertEquals(listOf(home.resolve(".claude/.config.json"), home.resolve(".claude.json")), paths)
    }

    @Test
    fun `disabled servers from canonical scopes never reappear in another working directory`() = runTest {
        val global = buildJsonObject {
            put("mcpServers", buildJsonObject { put("disabled", buildJsonObject { put("command", "tool") }) })
            put(
                "projects",
                buildJsonObject {
                    put(
                        "/another/canonical/root",
                        buildJsonObject {
                            put("disabledMcpServers", Json.parseToJsonElement("[\"disabled\"]"))
                        },
                    )
                },
            )
        }
        assertTrue(claudeNativeMcp(enabled, workspace, home) { global }.isEmpty())
    }

    @Test
    fun `malformed disable metadata fails closed`() = runTest {
        val global = Json.parseToJsonElement(
            """{"mcpServers":{"tool":{"command":"x"}},"projects":{"root":{"disabledMcpServers":{}}}}""",
        ).jsonObject
        assertFailsWith<IOException> { claudeNativeMcp(enabled, workspace, home) { global } }
    }

    @Test
    fun `a nested working directory never imports another projects local definitions`() = runTest {
        val servers = buildJsonObject { put("parentOnly", buildJsonObject { put("command", "x") }) }
        val global = buildJsonObject {
            put(
                "projects",
                buildJsonObject {
                    put(
                        workspace.parent.toString(),
                        buildJsonObject {
                            put("mcpServers", servers)
                        },
                    )
                },
            )
        }
        assertTrue(claudeNativeMcp(enabled, workspace, home) { global }.isEmpty())
    }

    @Test
    fun `Windows scope keys normalize case separators and trailing slash`() {
        assertEquals("c:/users/test/project", claudeMcpScopeKey("C:\\Users\\Test\\PROJECT\\", isWindows = true))
        assertEquals("c:/users/test/project", claudeMcpScopeKey("c:/users/test/project", isWindows = true))
        assertEquals("/Users/Test", claudeMcpScopeKey("/Users/Test/", isWindows = false))
    }

    @Test
    fun `canonical aliases cannot enter the trusted host namespace`() {
        for (name in listOf(
            HOSTED_TOOLS_SERVER,
            "${HOSTED_TOOLS_SERVER}_",
            "${HOSTED_TOOLS_SERVER}__evil",
            HOSTED_TOOLS_SERVER.replace('_', '.'),
            "${HOSTED_TOOLS_SERVER}..evil",
            "${HOSTED_TOOLS_SERVER}\uD83D\uDE00evil",
            "${HOSTED_SEARCH_SERVER}\uD83D\uDE00evil",
        )) {
            assertTrue(isHostedMcpNamespace(name), name)
        }
        assertFalse(isHostedMcpNamespace("${HOSTED_TOOLS_SERVER}_other"))
        assertFalse(isHostedMcpNamespace("ordinary.server"))
    }

    @Test
    fun `captured environment chooses config directory without reading project config`() = runTest {
        val paths = mutableListOf<Path>()
        claudeNativeMcp(
            enabled.copy(environment = listOf(EnvironmentEntry("CLAUDE_CONFIG_DIR", "custom"))),
            workspace,
            home,
        ) {
            paths.add(it)
            null
        }
        assertEquals(listOf(workspace.resolve("custom/.config.json"), workspace.resolve("custom/.claude.json")), paths)
        assertTrue(paths.none { it.fileName.toString() == ".mcp.json" })
    }

    @Test
    fun `bounded reader rejects malformed and oversized config without source text in parser error`() {
        val directory = Files.createTempDirectory("native-mcp-test")
        val file = directory.resolve("config.json")
        try {
            assertEquals(null, readClaudeMcpObject(file))
            Files.writeString(file, "secret-not-json")
            val failure = assertFailsWith<IOException> { readClaudeMcpObject(file) }
            assertFalse(failure.toString().contains("secret"))
            assertEquals(null, failure.cause)
            Files.write(file, ByteArray(1024 * 1024 + 1) { ' '.code.toByte() })
            assertFailsWith<IOException> { readClaudeMcpObject(file) }
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `explicit merged config preserves native secrets and host bridge precedence`() {
        val directory = Files.createTempDirectory("merged-mcp-test")
        val bridge = directory.resolve("bridge.json")
        try {
            Files.writeString(bridge, """{"mcpServers":{"$HOSTED_TOOLS_SERVER":{"url":"host"}}}""")
            val imported = Json.parseToJsonElement(
                """{"native":{"env":{"TOKEN":"secret"}},"$HOSTED_TOOLS_SERVER":{"url":"spoof"}}""",
            ).jsonObject
            assertEquals(bridge, mergeClaudeMcp(imported, bridge, directory))
            val result = checkNotNull(readClaudeMcpObject(bridge))["mcpServers"] as JsonObject
            assertTrue(result.toString().contains("secret"))
            assertTrue(result.toString().contains("host"))
            assertFalse(result.toString().contains("spoof"))
        } finally {
            Files.deleteIfExists(bridge)
            Files.deleteIfExists(directory)
        }
    }
}
