package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnvironmentEntry
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchProblem
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchProblemReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedInstall
import io.aequicor.heartbeat.feature.aiengine.facade.api.RuntimePreference
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import org.junit.Assume.assumeTrue
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ClaudeStartupTest {
    private val config = ClaudeConfiguration()
    private val localClaude = File(File(File("/Users/test", ".local"), "bin"), "claude").path
    private val managed = ManagedInstall("2.1.285", "/data/claude/claude", Instant.fromEpochSeconds(1))

    @Test
    fun `MCP access is captured from the selected launch rather than enabled by its installation`() {
        val enabledContext = LaunchContext(LaunchSettings(executable = "/custom/claude", isMcpEnabled = true))
        val captured = resolveClaudeStartup(enabledContext, config, host())
        assertTrue(captured.isMcpEnabled)
        val disabledContext = enabledContext.copy(settings = enabledContext.settings.copy(isMcpEnabled = false))
        assertFalse(resolveClaudeStartup(disabledContext, config, host()).isMcpEnabled)
        assertTrue(captured.isMcpEnabled)
        assertFalse(resolveClaudeStartup(LaunchContext(managed = managed), config, host()).isMcpEnabled)
    }

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
    fun `Linux discovers the native installer location and prefers PATH`() {
        val local = File("/home/test/.local/bin/claude").path
        val custom = File("/custom/bin/claude").path
        val linux = host(osName = "Linux", userHome = "/home/test", available = setOf(local, custom))
        val installed = resolveClaudeStartup(LaunchContext(), config, linux)
        assertEquals(local to InstallSource.System, installed.executable to installed.source)
        assertTrue(installed.isRunnable)
        assertEquals(
            custom,
            resolveClaudeStartup(LaunchContext(), config, linux.copy(path = "/custom/bin")).executable,
        )
        assertEquals(
            InstallSource.Missing,
            resolveClaudeStartup(
                LaunchContext(),
                config,
                host(osName = "Linux", available = setOf(File("/opt/homebrew/bin/claude").path)),
            ).source,
        )
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
        assertEquals(
            mapOf("PATH" to "/bin", "HTTPS_PROXY" to "http://proxy", "ENABLE_TOOL_SEARCH" to "false"),
            claudeEnvironment(host, system, isDirectory = { false }),
        )

        val settings = LaunchSettings(
            homeDirectory = "/Users/me/.claude-work",
            environment = listOf(
                EnvironmentEntry("NO_PROXY", "localhost"),
                EnvironmentEntry("ENABLE_TOOL_SEARCH", "true"),
            ),
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
                "ENABLE_TOOL_SEARCH" to "true",
                "CLAUDE_CONFIG_DIR" to "/Users/me/.claude-work",
            ),
            claudeEnvironment(host, configured, isDirectory = { false }),
        )
    }

    @Test
    fun `a minimal launcher PATH gains the usual tool directories after the inherited entries`() {
        val startup = resolveClaudeStartup(LaunchContext(), config.copy(executable = "/opt/claude"), host())
        val existing = setOf("/opt/homebrew/bin", "/usr/local/bin", "/Users/me/.local/bin")

        val macOs = claudeEnvironment(
            mapOf("PATH" to "/usr/bin:/bin"),
            startup,
            osName = "Mac OS X",
            userHome = "/Users/me",
            isDirectory = existing::contains,
        )
        val linux = claudeEnvironment(
            mapOf("PATH" to "/usr/local/bin:/usr/bin"),
            startup,
            osName = "Linux",
            userHome = "/Users/me",
            isDirectory = existing::contains,
        )
        val windows = claudeEnvironment(
            mapOf("PATH" to "C:\\Windows"),
            startup,
            osName = "Windows 11",
            userHome = "C:\\Users\\me",
            isDirectory = { true },
        )

        assertEquals("/usr/bin:/bin:/opt/homebrew/bin:/usr/local/bin:/Users/me/.local/bin", macOs["PATH"])
        assertEquals("/usr/local/bin:/usr/bin:/Users/me/.local/bin", linux["PATH"])
        assertEquals("C:\\Windows", windows["PATH"])
    }

    @Test
    fun `a host without PATH keeps the system default and trailing slashes do not duplicate entries`() {
        val startup = resolveClaudeStartup(LaunchContext(), config.copy(executable = "/opt/claude"), host())
        fun environment(host: Map<String, String>) = claudeEnvironment(
            host,
            startup,
            osName = "Linux",
            userHome = "/Users/me",
            isDirectory = { true },
        )

        assertNull(environment(emptyMap())["PATH"])
        assertEquals("  ", environment(mapOf("PATH" to "  "))["PATH"])
        assertEquals(
            "/usr/local/bin/:/Users/me/.local/bin",
            environment(mapOf("PATH" to "/usr/local/bin/"))["PATH"],
        )
    }

    @Test
    fun `Heartbeat's copy never updates itself`() {
        val startup = resolveClaudeStartup(LaunchContext(managed = managed), config, host())

        val environment = claudeEnvironment(emptyMap(), startup, isDirectory = { false })

        assertEquals(
            mapOf("ENABLE_TOOL_SEARCH" to "false", "DISABLE_AUTOUPDATER" to "1", "DISABLE_UPDATES" to "1"),
            environment,
        )
    }

    @Test
    fun `the Claude Code desktop CLI is the last resort and its newest verified build is chosen`() {
        val root = "/Users/test/Library/Application Support/Claude/claude-code"
        fun executable(version: String, build: String) =
            File("$root/$version/$build/claude.app/Contents/MacOS/claude").path
        val desktop = host(
            directories = mapOf(
                root to listOf("2.1.9", "2.1.299", "2.1.300", "notes"),
                "$root/2.1.300" to listOf("bbb"),
                "$root/2.1.300/bbb" to listOf("claude.app"),
                "$root/2.1.299" to listOf("4fa"),
                "$root/2.1.299/4fa" to listOf(".verified", "claude.app"),
                "$root/2.1.9" to listOf("aaa"),
                "$root/2.1.9/aaa" to listOf(".verified", "claude.app"),
            ),
            available = setOf(executable("2.1.300", "bbb"), executable("2.1.299", "4fa"), executable("2.1.9", "aaa")),
        )

        val startup = resolveClaudeStartup(LaunchContext(), config, desktop)

        assertEquals(executable("2.1.299", "4fa"), startup.executable)
        assertEquals(InstallSource.HostApp, startup.source)
        assertTrue(startup.isRunnable)
        val withLocal = desktop.copy(isExecutable = { it.path == localClaude || desktop.isExecutable(it) })
        val preferred = resolveClaudeStartup(LaunchContext(), config, withLocal)
        assertEquals(localClaude to InstallSource.System, preferred.executable to preferred.source)
        assertEquals(InstallSource.Missing, resolveClaudeStartup(LaunchContext(), config, host()).source)
        assertEquals(
            InstallSource.Missing,
            resolveClaudeStartup(LaunchContext(), config, desktop.copy(osName = "Linux")).source,
        )
    }

    @Test
    fun `the desktop CLI never updates itself`() {
        val startup = ClaudeStartup("/desktop/claude", InstallSource.HostApp, isRunnable = true)

        assertEquals(
            mapOf("ENABLE_TOOL_SEARCH" to "false", "DISABLE_AUTOUPDATER" to "1", "DISABLE_UPDATES" to "1"),
            claudeEnvironment(emptyMap(), startup, isDirectory = { false }),
        )
    }

    @Test
    fun `the native history is searched for a conversation in every project directory`() {
        val home = Files.createTempDirectory("claude-history").toFile()
        try {
            val id = "0b7c6e0e-6a3b-4f0e-9d51-4f5f6e1d9a11"
            assertFalse(claudeTranscriptExists(null, id, home.path), "no history holds no conversation")
            File(home, ".claude/projects/-Users-me-project").mkdirs()
            File(home, ".claude/projects/-Users-me-other").mkdirs()
            assertFalse(claudeTranscriptExists(null, id, home.path))
            File(home, ".claude/projects/-Users-me-other/$id.jsonl").writeText("{}")
            assertTrue(claudeTranscriptExists(null, id, home.path))
            assertFalse(claudeTranscriptExists(null, "another-id", home.path))
            val custom = File(home, "custom").apply { File(this, "projects/p").mkdirs() }
            File(custom, "projects/p/$id.jsonl").writeText("{}")
            assertTrue(claudeTranscriptExists(custom.path, id, "/nowhere"), "a config directory has its own history")
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun `a stray file among the project directories holds no conversation and is not a read failure`() {
        val home = Files.createTempDirectory("claude-history").toFile()
        try {
            val id = "0b7c6e0e-6a3b-4f0e-9d51-4f5f6e1d9a11"
            val projects = File(home, ".claude/projects").apply { mkdirs() }
            File(projects, ".DS_Store").writeText("not a directory")
            File(projects, "-Users-me-project").mkdirs()
            assertFalse(claudeTranscriptExists(null, id, home.path))
            File(projects, "-Users-me-project/$id.jsonl").writeText("{}")
            assertTrue(claudeTranscriptExists(null, id, home.path))
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun `an unreadable project directory is a read failure, not an absent conversation`() {
        val home = Files.createTempDirectory("claude-history").toFile()
        val project = File(home, ".claude/projects/-Users-me-project").apply { mkdirs() }
        try {
            // Where the permission cannot be taken away (Windows, root) there is nothing to observe.
            project.setReadable(false)
            project.setExecutable(false)
            assumeTrue(!project.canRead())
            val id = "0b7c6e0e-6a3b-4f0e-9d51-4f5f6e1d9a11"
            assertFailsWith<IOException> { claudeTranscriptExists(null, id, home.path) }
        } finally {
            project.setReadable(true)
            project.setExecutable(true)
            home.deleteRecursively()
        }
    }

    @Test
    fun `a history directory whose entries cannot be inspected is a read failure, not an absent conversation`() {
        val home = Files.createTempDirectory("claude-history").toFile()
        val projects = File(home, ".claude/projects").apply { File(this, "-Users-me-project").mkdirs() }
        try {
            // Listing works without the search permission, inspecting the entries does not.
            projects.setExecutable(false)
            assumeTrue(projects.canRead() && !projects.canExecute())
            val id = "0b7c6e0e-6a3b-4f0e-9d51-4f5f6e1d9a11"
            assertFailsWith<IOException> { claudeTranscriptExists(null, id, home.path) }
        } finally {
            projects.setExecutable(true)
            home.deleteRecursively()
        }
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
    fun `builds exist for macOS Windows and glibc Linux on arm64 and x64`() {
        assertEquals("darwin-arm64", claudeReleasePlatform(host(osArch = "aarch64")))
        assertEquals("darwin-x64", claudeReleasePlatform(host(osArch = "x86_64")))
        assertEquals("win32-x64", claudeReleasePlatform(host(osName = "Windows 11", osArch = "amd64")))
        assertEquals("linux-x64", claudeReleasePlatform(host(osName = "Linux", osArch = "amd64")))
        assertEquals("linux-arm64", claudeReleasePlatform(host(osName = "Linux", osArch = "aarch64")))
        assertNull(claudeReleasePlatform(host(osName = "FreeBSD")))
        assertNull(claudeReleasePlatform(host(osArch = "ppc64")))
    }

    @Test
    fun `the version is read from claude --version`() {
        assertEquals("2.1.285", parseClaudeVersion("2.1.285 (Claude Code)"))
        assertNull(parseClaudeVersion("command not found"))
    }

    @Test
    fun `a runtime preference runs only that installation and reports it missing otherwise`() {
        val root = "/Users/test/Library/Application Support/Claude/claude-code"
        val desktopExe = File("$root/2.1.304/b2/claude.app/Contents/MacOS/claude").path
        val pinnedExe = File("$root/2.1.299/b1/claude.app/Contents/MacOS/claude").path
        val mac = host(
            directories = mapOf(
                root to listOf("2.1.299", "2.1.304"),
                "$root/2.1.304" to listOf("b2"),
                "$root/2.1.304/b2" to listOf(".verified", "claude.app"),
                "$root/2.1.299" to listOf("b1"),
                "$root/2.1.299/b1" to listOf(".verified", "claude.app"),
            ),
            available = setOf(localClaude, desktopExe, pinnedExe),
        )
        fun resolve(settings: LaunchSettings, managedCopy: ManagedInstall? = managed, enabled: Boolean = true) =
            resolveClaudeStartup(LaunchContext(settings, managedCopy), config, mac, enabled)
                .let { it.executable to it.source }

        val hostApp = LaunchSettings(runtime = RuntimePreference.HostApp)
        assertEquals(desktopExe to InstallSource.HostApp, resolve(hostApp))
        assertEquals(pinnedExe to InstallSource.HostApp, resolve(hostApp.copy(hostRuntimeVersion = "2.1.299")))
        assertEquals(InstallSource.Missing, resolve(hostApp.copy(hostRuntimeVersion = "2.0.0")).second)
        assertEquals(localClaude to InstallSource.System, resolve(LaunchSettings(runtime = RuntimePreference.System)))
        assertEquals(
            InstallSource.Missing,
            resolve(LaunchSettings(runtime = RuntimePreference.Managed), managedCopy = null).second,
        )
        assertEquals(InstallSource.Managed, resolve(LaunchSettings(runtime = RuntimePreference.Managed)).second)
        assertEquals(InstallSource.Custom, resolve(hostApp.copy(executable = "/custom/claude")).second)
        // Switched off, the desktop preference falls back to the automatic order.
        assertEquals(InstallSource.Managed, resolve(hostApp, enabled = false).second)
    }

    @Test
    fun `a system preference never falls back to the desktop app`() {
        val root = "/Users/test/Library/Application Support/Claude/claude-code"
        val desktopExe = File("$root/2.1.304/b2/claude.app/Contents/MacOS/claude").path
        val mac = host(
            directories = mapOf(
                root to listOf("2.1.304"),
                "$root/2.1.304" to listOf("b2"),
                "$root/2.1.304/b2" to listOf(".verified"),
            ),
            available = setOf(desktopExe),
        )
        val system = LaunchContext(LaunchSettings(runtime = RuntimePreference.System))
        assertEquals(InstallSource.Missing, resolveClaudeStartup(system, config, mac).source)
        assertEquals(InstallSource.HostApp, resolveClaudeStartup(LaunchContext(), config, mac).source)
    }

    @Test
    fun `a preferred desktop runtime that is missing is a launch warning`() {
        val hostApp = LaunchSettings(runtime = RuntimePreference.HostApp)
        assertEquals(
            listOf(LaunchProblem(LaunchOption.Runtime, LaunchProblemReason.NotFound)),
            claudeLaunchProblems(hostApp, host()),
        )
        assertEquals(
            listOf(LaunchProblem(LaunchOption.Runtime, LaunchProblemReason.Unsupported)),
            claudeLaunchProblems(hostApp, host(), isHostAppEnabled = false),
        )
        assertEquals(emptyList(), claudeLaunchProblems(LaunchSettings(), host()))
    }

    private fun host(
        osName: String = "Mac OS X",
        osArch: String = "aarch64",
        path: String? = null,
        userHome: String = "/Users/test",
        available: Set<String> = emptySet(),
        directories: Map<String, List<String>> = emptyMap(),
    ): ClaudeHost = testClaudeHost(osName, osArch, path, userHome, available, directories)
}

/** A host whose files exist only as listed: [directories] by `/`-spelled path, [files] with their text. */
@Suppress("LongParameterList") // Every host fact a test may vary.
internal fun testClaudeHost(
    osName: String = "Mac OS X",
    osArch: String = "aarch64",
    path: String? = null,
    userHome: String = "/Users/test",
    available: Set<String> = emptySet(),
    directories: Map<String, List<String>> = emptyMap(),
    files: Map<String, String> = emptyMap(),
    environment: Map<String, String> = emptyMap(),
): ClaudeHost {
    // The keys are written with `/`; the host looks files up by the platform's own spelling of the path.
    val listed = directories.mapKeys { File(it.key).path }
    val executables = available.map { File(it).path }.toSet()
    val texts = files.mapKeys { File(it.key).path }
    return ClaudeHost(
        osName = osName,
        osArch = osArch,
        path = path,
        userHome = userHome,
        entries = { directory -> listed[directory.path].orEmpty().map { File(directory, it) } },
        isExecutable = { it.path in executables },
        environment = environment,
        exists = { it.path in listed || it.path in executables || it.path in texts },
        readText = { texts[it.path] },
    )
}
