package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.codex.api.CodexLocalConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigOverride
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
import kotlin.test.assertNull
import kotlin.time.Instant

class CodexLaunchTest {
    private val config = CodexLocalConfiguration()
    private val managed = ManagedInstall("0.159.3", "/data/codex/bin/codex", Instant.fromEpochSeconds(1))

    @Test
    fun `a custom executable wins over Heartbeat's copy, which wins over the system`() {
        val mac = host(available = setOf("/opt/homebrew/bin/codex"))
        val custom = LaunchContext(LaunchSettings(executable = "/custom/codex"), managed)

        assertEquals(
            Triple("/custom/codex", InstallSource.Custom, true),
            resolveCodexLaunch(custom, config, mac).let { Triple(it.executable, it.source, it.isRunnable) },
        )
        assertEquals(InstallSource.Managed, resolveCodexLaunch(LaunchContext(managed = managed), config, mac).source)
        val system = resolveCodexLaunch(LaunchContext(), config, mac)
        assertEquals("/opt/homebrew/bin/codex" to InstallSource.System, system.executable to system.source)
        assertEquals(InstallSource.Missing, resolveCodexLaunch(LaunchContext(), config, host()).source)
    }

    @Test
    fun `windows starts codex exe from PATH and never an npm cmd shim`() {
        val exe = File("C:\\tools", "codex.exe").path
        val windows = host(osName = "Windows 11", path = "C:\\npm;C:\\tools", available = setOf(exe))
        assertEquals(exe, resolveCodexLaunch(LaunchContext(), config, windows).executable)

        val npm = Files.createTempDirectory("codex-npm")
        try {
            File(npm.toFile(), "codex.cmd").writeText("@node codex.js %*")
            val shim = resolveCodexLaunch(LaunchContext(), config, host(osName = "Windows 11", path = npm.toString()))
            assertEquals(InstallSource.System, shim.source)
            assertFalse(shim.isRunnable)
        } finally {
            npm.toFile().deleteRecursively()
        }
        val desktopApp = Files.createTempDirectory("codex-desktop")
        try {
            val cached = File(desktopApp.toFile(), "OpenAI/Codex/bin/build").apply { mkdirs() }.resolve("codex.exe")
            cached.writeText("exe")
            val noPath = host(
                osName = "Windows 11",
                localAppData = desktopApp.toString(),
                available = setOf(cached.path),
            )
            assertEquals(
                cached.path to InstallSource.System,
                resolveCodexLaunch(LaunchContext(), config, noPath).let { it.executable to it.source },
            )
        } finally {
            desktopApp.toFile().deleteRecursively()
        }
        val wrapper = resolveCodexLaunch(LaunchContext(LaunchSettings(executable = "C:\\codex.bat")), config, windows)
        assertFalse(wrapper.isRunnable)
        assertFalse(resolveCodexLaunch(LaunchContext(), config.copy(executable = "C:\\codex.cmd"), windows).isRunnable)
    }

    @Test
    fun `the command keeps Heartbeat's isolation flags after the user's overrides`() {
        val launch = CodexLaunch(
            "/bin/codex",
            InstallSource.Custom,
            isRunnable = true,
            home = null,
            overrides = listOf(ConfigOverride("model_reasoning_summary", "\"auto\"")),
        )

        val command = codexCommand(launch)

        assertEquals(listOf("/bin/codex", "app-server", "-c", "model_reasoning_summary=\"auto\""), command.take(4))
        assertEquals(listOf("-c", "model_provider=\"openai\""), command.subList(4, 6))
        assertEquals(CodexDisabledCapabilities.size * 2, command.size - 6)
    }

    @Test
    fun `the environment drops API keys and adds the user's entries and CODEX_HOME`() {
        val launch = CodexLaunch(
            "/bin/codex",
            InstallSource.Managed,
            isRunnable = true,
            home = "/Users/me/.codex-work",
            environment = listOf(EnvironmentEntry("HTTPS_PROXY", "http://proxy:3128")),
        )
        val environment = mutableMapOf("OPENAI_API_KEY" to "sk", "CODEX_API_KEY" to "k", "PATH" to "/bin")

        applyCodexEnvironment(environment, launch)

        assertEquals(
            mapOf("PATH" to "/bin", "HTTPS_PROXY" to "http://proxy:3128", "CODEX_HOME" to "/Users/me/.codex-work"),
            environment,
        )
        assertEquals(
            "/home/cfg",
            resolveCodexLaunch(LaunchContext(), config.copy(homeDirectory = "/home/cfg"), host()).home,
        )
    }

    @Test
    fun `missing files are reported as warnings`() {
        val directory = Files.createTempDirectory("codex-home")
        try {
            val file = File(directory.toFile(), "file").apply { writeText("x") }
            val settings = LaunchSettings(executable = "/missing/codex", homeDirectory = file.path)

            assertEquals(
                listOf(
                    LaunchProblem(LaunchOption.Executable, LaunchProblemReason.NotFound),
                    LaunchProblem(LaunchOption.HomeDirectory, LaunchProblemReason.NotADirectory),
                ),
                codexLaunchProblems(settings),
            )
            assertEquals(emptyList(), codexLaunchProblems(LaunchSettings(homeDirectory = directory.toString())))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `packages exist for macOS and Windows on arm64 and x64`() {
        assertEquals(CodexTarget("aarch64-apple-darwin", "bin/codex"), codexReleaseTarget(host(osArch = "aarch64")))
        assertEquals(
            CodexTarget("x86_64-pc-windows-msvc", "bin/codex.exe"),
            codexReleaseTarget(host(osName = "Windows 11", osArch = "amd64")),
        )
        assertNull(codexReleaseTarget(host(osName = "Linux")))
        assertNull(codexReleaseTarget(host(osArch = "ppc64")))
    }

    @Test
    fun `the version is read from codex --version`() {
        assertEquals("0.159.2", parseCodexVersion("codex-cli 0.159.2\n"))
        assertEquals("0.161.0-alpha.3", parseCodexVersion("codex-cli 0.161.0-alpha.3"))
        assertNull(parseCodexVersion("command not found"))
    }

    private fun host(
        osName: String = "Mac OS X",
        osArch: String = "aarch64",
        path: String? = null,
        available: Set<String> = emptySet(),
        localAppData: String? = null,
    ) = CodexHost(osName, osArch, path, "/Users/test", localAppData) { it.path in available }
}
