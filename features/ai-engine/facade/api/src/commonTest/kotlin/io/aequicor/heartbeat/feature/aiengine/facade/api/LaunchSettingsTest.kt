package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LaunchSettingsTest {
    private val spec = LaunchSpec(
        options = LaunchOption.entries.toSet(),
        homeVariable = "CODEX_HOME",
        reservedEnvironment = setOf("CODEX_HOME"),
        reservedEnvironmentPrefixes = setOf("PI_"),
        reservedConfigKeys = setOf("model_provider", "features"),
    )

    @Test
    fun `default settings are valid for any engine`() {
        assertTrue(LaunchSettings().isDefault)
        assertEquals(emptyList(), validateLaunchSettings(LaunchSettings(), LaunchSpec(), EnginePlatform.DesktopMacOs))
    }

    @Test
    fun `paths must be absolute on both desktop hosts`() {
        val accepted = listOf(
            "/usr/local/bin/codex",
            "C:\\Tools\\codex.exe",
            "C:/Tools/codex.exe",
            "\\\\server\\codex.exe",
        )
        accepted.forEach { path ->
            assertEquals(emptyList(), problems(LaunchSettings(homeDirectory = path)), path)
        }
        assertEquals(
            listOf(LaunchProblem(LaunchOption.Executable, LaunchProblemReason.NotAbsolute)),
            problems(LaunchSettings(executable = "bin/codex")),
        )
    }

    @Test
    fun `windows starts only exe files and refuses script wrappers`() {
        val windows = EnginePlatform.DesktopWindows
        assertEquals(emptyList(), validateLaunchSettings(LaunchSettings(executable = "C:\\codex.EXE"), spec, windows))
        assertEquals(
            listOf(LaunchProblem(LaunchOption.Executable, LaunchProblemReason.ScriptWrapper)),
            validateLaunchSettings(LaunchSettings(executable = "C:\\npm\\codex.cmd"), spec, windows),
        )
        assertEquals(
            listOf(LaunchProblem(LaunchOption.Executable, LaunchProblemReason.NotExe)),
            validateLaunchSettings(LaunchSettings(executable = "C:\\tools\\codex"), spec, windows),
        )
        assertEquals(emptyList(), problems(LaunchSettings(executable = "/opt/codex/bin/codex")))
    }

    @Test
    fun `settings an engine does not honor are rejected`() {
        val executableOnly = LaunchSpec(options = setOf(LaunchOption.Executable))
        val settings = LaunchSettings(
            homeDirectory = "/home",
            configOverrides = listOf(ConfigOverride("model", "o3")),
            environment = listOf(EnvironmentEntry("HTTPS_PROXY", "http://proxy")),
        )

        assertEquals(
            listOf(LaunchOption.HomeDirectory, LaunchOption.ConfigOverrides, LaunchOption.Environment),
            validateLaunchSettings(settings, executableOnly, EnginePlatform.DesktopMacOs).map { it.option },
        )
    }

    @Test
    fun `credential variables belong in connections`() {
        val names = listOf(
            "OPENAI_API_KEY", "ANTHROPIC_BASE_URL", "GITHUB_TOKEN", "MY_SECRET", "DB_PASSWORD", "API_KEY",
            "CODEX_API_KEY", "CLAUDE_CODE_OAUTH_TOKEN", "CLAUDE_CODE_USE_BEDROCK", "aws_credentials", "SERVICE_APIKEY",
        )
        names.forEach { name ->
            assertTrue(isSecretVariable(name), name)
            assertEquals(
                listOf(LaunchProblem(LaunchOption.Environment, LaunchProblemReason.Secret, 0)),
                problems(LaunchSettings(environment = listOf(EnvironmentEntry(name, "value")))),
                name,
            )
        }
        listOf("HTTPS_PROXY", "NO_PROXY", "KEYCHAIN_PATH", "RUST_LOG", "PATH").forEach { name ->
            assertFalse(isSecretVariable(name), name)
        }
    }

    @Test
    fun `environment entries are checked for names reservations duplicates and line breaks`() {
        val settings = LaunchSettings(
            environment = listOf(
                EnvironmentEntry("1BAD", "x"),
                EnvironmentEntry("codex_home", "/tmp"),
                EnvironmentEntry("PI_OFFLINE", "1"),
                EnvironmentEntry("HEARTBEAT_AGENT_TOOLS_URL", "x"),
                EnvironmentEntry("HTTPS_PROXY", "http://a"),
                EnvironmentEntry("https_proxy", "http://b"),
                EnvironmentEntry("NO_PROXY", "a\nb"),
            ),
        )

        assertEquals(
            listOf(
                LaunchProblemReason.InvalidName to 0,
                LaunchProblemReason.Reserved to 1,
                LaunchProblemReason.Reserved to 2,
                LaunchProblemReason.Reserved to 3,
                LaunchProblemReason.Duplicate to 5,
                LaunchProblemReason.ControlCharacter to 6,
            ),
            problems(settings).map { it.reason to it.index },
        )
    }

    @Test
    fun `configuration overrides keep reserved keys and their children`() {
        val settings = LaunchSettings(
            configOverrides = listOf(
                ConfigOverride("model_reasoning_summary", "\"auto\""),
                ConfigOverride("model_provider", "\"other\""),
                ConfigOverride("features.web_search", "true"),
                ConfigOverride("model_providerx", "1"),
                ConfigOverride("bad key", "1"),
                ConfigOverride("model_reasoning_summary", "\"none\""),
            ),
        )

        assertEquals(
            listOf(
                LaunchProblemReason.Reserved to 1,
                LaunchProblemReason.Reserved to 2,
                LaunchProblemReason.InvalidKey to 4,
                LaunchProblemReason.Duplicate to 5,
            ),
            problems(settings).map { it.reason to it.index },
        )
    }

    @Test
    fun `too many entries are refused as a whole`() {
        val settings = LaunchSettings(environment = List(65) { EnvironmentEntry("VAR_$it", "x") })

        assertEquals(
            listOf(LaunchProblem(LaunchOption.Environment, LaunchProblemReason.TooLong)),
            problems(settings),
        )
    }

    @Test
    fun `settings round trip through storage but never print their values`() {
        val settings = LaunchSettings(
            executable = "/Users/me/bin/codex",
            homeDirectory = "/Users/me/.codex-work",
            configOverrides = listOf(ConfigOverride("model", "\"o3\"")),
            environment = listOf(EnvironmentEntry("HTTPS_PROXY", "http://user@proxy")),
        )

        assertEquals(settings, Json.decodeFromString(LaunchSettings.serializer(), Json.encodeToString(settings)))
        val printed = "$settings ${settings.configOverrides} ${settings.environment}"
        listOf("/Users/me", "o3", "proxy").forEach { assertFalse(it in printed, it) }
        assertFalse(settings.isDefault)
    }

    private fun problems(settings: LaunchSettings) = validateLaunchSettings(settings, spec, EnginePlatform.DesktopMacOs)
}
