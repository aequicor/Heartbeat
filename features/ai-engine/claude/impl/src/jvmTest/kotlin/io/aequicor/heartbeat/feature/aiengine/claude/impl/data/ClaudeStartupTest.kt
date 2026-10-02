package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnvironmentEntry
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchProblem
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchProblemReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedInstall
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class ClaudeStartupTest {
    private val config = ClaudeConfiguration()
    private val localClaude = File(File(File("/Users/test", ".local"), "bin"), "claude").path
    private val managed = ManagedInstall("2.1.285", "/data/claude/claude", Instant.fromEpochSeconds(1))

    @Test
    fun `a custom executable wins over Heartbeat's copy, which wins over the system`() {
        val mac = host(available = setOf(localClaude))
        val custom = LaunchContext(LaunchSettings(executable = "/custom/claude"), managed)

        assertEquals(InstallSource.Custom, resolveClaudeStartup(custom, config, mac).source)
        assertEquals(
            "/data/claude/claude" to InstallSource.Managed,
            resolveClaudeStartup(LaunchContext(managed = managed), config, mac).let { it.executable to it.source },
        )
        val system = resolveClaudeStartup(LaunchContext(), config, mac)
        assertEquals(localClaude to InstallSource.System, system.executable to system.source)
        assertEquals(
            InstallSource.System,
            resolveClaudeStartup(LaunchContext(), config.copy(executable = "/opt/claude"), host()).source,
        )
        val missing = resolveClaudeStartup(LaunchContext(), config, host())
        assertEquals(InstallSource.Missing, missing.source)
        assertFalse(missing.isRunnable)
    }

    @Test
    fun `windows starts claude exe and never an npm cmd shim`() {
        val exe = File("C:\\tools", "claude.exe").path
        val windows = host(osName = "Windows 11", path = "C:\\npm;C:\\tools", available = setOf(exe))
        assertEquals(exe, resolveClaudeStartup(LaunchContext(), config, windows).executable)

        val npm = Files.createTempDirectory("claude-npm")
        try {
            File(npm.toFile(), "claude.cmd").writeText("@node claude.js %*")
            val shim = resolveClaudeStartup(LaunchContext(), config, host(osName = "Windows 11", path = npm.toString()))
            assertEquals(InstallSource.System to false, shim.source to shim.isRunnable)
        } finally {
            npm.toFile().deleteRecursively()
        }
        val wrapper = LaunchContext(LaunchSettings(executable = "C:\\claude.bat"))
        assertFalse(resolveClaudeStartup(wrapper, config, windows).isRunnable)
    }

    @Test
    fun `the environment drops credentials and adds the user's entries and config directory`() {
        val host = mapOf(
            "PATH" to "/bin",
            "HTTPS_PROXY" to "http://proxy",
            "ANTHROPIC_API_KEY" to "secret",
            "ANTHROPIC_BASE_URL" to "http://elsewhere",
            "CLAUDE_CONFIG_DIR" to "/elsewhere",
        )
        val system = resolveClaudeStartup(LaunchContext(), config.copy(executable = "/opt/claude"), host())
        assertEquals(mapOf("PATH" to "/bin", "HTTPS_PROXY" to "http://proxy"), claudeEnvironment(host, system))

        val settings = LaunchSettings(
            homeDirectory = "/Users/me/.claude-work",
            environment = listOf(EnvironmentEntry("NO_PROXY", "localhost")),
        )
        val configured = resolveClaudeStartup(
            LaunchContext(settings),
            config.copy(configDirectory = "/configured"),
            host(),
        )
        assertEquals(
            mapOf(
                "PATH" to "/bin",
                "HTTPS_PROXY" to "http://proxy",
                "NO_PROXY" to "localhost",
                "CLAUDE_CONFIG_DIR" to "/Users/me/.claude-work",
            ),
            claudeEnvironment(host, configured),
        )
    }

    @Test
    fun `Heartbeat's copy never updates itself`() {
        val startup = resolveClaudeStartup(LaunchContext(managed = managed), config, host())

        val environment = claudeEnvironment(emptyMap(), startup)

        assertEquals(mapOf("DISABLE_AUTOUPDATER" to "1", "DISABLE_UPDATES" to "1"), environment)
    }

    @Test
    fun `the native history follows the config directory`() {
        val default = claudeNativeStore(null, "/Users/me")
        assertEquals(default, claudeNativeStore("/Users/me/.claude", "/Users/other"))
        assertNotEquals(default, claudeNativeStore("/Users/me/.claude-work", "/Users/me"))
    }

    @Test
    fun `missing files are reported as warnings`() {
        val directory = Files.createTempDirectory("claude-config")
        try {
            val file = File(directory.toFile(), "file").apply { writeText("x") }
            val settings = LaunchSettings(executable = "/missing/claude", homeDirectory = file.path)

            assertEquals(
                listOf(
                    LaunchProblem(LaunchOption.Executable, LaunchProblemReason.NotFound),
                    LaunchProblem(LaunchOption.HomeDirectory, LaunchProblemReason.NotADirectory),
                ),
                claudeLaunchProblems(settings),
            )
            assertEquals(emptyList(), claudeLaunchProblems(LaunchSettings(homeDirectory = directory.toString())))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `builds exist for macOS and Windows on arm64 and x64`() {
        assertEquals("darwin-arm64", claudeReleasePlatform(host(osArch = "aarch64")))
        assertEquals("darwin-x64", claudeReleasePlatform(host(osArch = "x86_64")))
        assertEquals("win32-x64", claudeReleasePlatform(host(osName = "Windows 11", osArch = "amd64")))
        assertNull(claudeReleasePlatform(host(osName = "Linux")))
        assertNull(claudeReleasePlatform(host(osArch = "ppc64")))
    }

    @Test
    fun `the version is read from claude --version`() {
        assertEquals("2.1.285", parseClaudeVersion("2.1.285 (Claude Code)"))
        assertNull(parseClaudeVersion("command not found"))
    }

    private fun host(
        osName: String = "Mac OS X",
        osArch: String = "aarch64",
        path: String? = null,
        userHome: String = "/Users/test",
        available: Set<String> = emptySet(),
    ) = ClaudeHost(osName, osArch, path, userHome) { it.path in available }
}
