package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.Compatibility
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnvironmentEntry
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedInstall
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ArchiveKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.GitHubAsset
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.GitHubRelease
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ReleaseFeeds
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

class PiStartupTest {
    private val bundled = Path.of("/app/resources/pi/pi")
    private val managed = ManagedInstall("1.0.0", "/data/pi/pi", Instant.fromEpochSeconds(1))
    private val download = "https://github.com/earendil-works/pi/releases/download/v1.0.0"
    private val digest = "97".repeat(32)

    @Test
    fun `a custom executable wins over Heartbeat's copy, which wins over the bundled Pi`() {
        val custom = LaunchContext(LaunchSettings(executable = "/custom/pi"), managed)

        assertEquals(Path.of("/custom/pi") to InstallSource.Custom, resolvePiStartup(custom, bundled).pair())
        assertEquals(
            Path.of("/data/pi/pi") to InstallSource.Managed,
            resolvePiStartup(LaunchContext(managed = managed), bundled).pair(),
        )
        assertEquals(bundled to InstallSource.Bundled, resolvePiStartup(LaunchContext(), bundled).pair())
        assertEquals(null to InstallSource.Missing, resolvePiStartup(LaunchContext(), null).pair())
        val unnamed = LaunchContext(LaunchSettings(executable = "C:\\pi\u0000.exe"))
        assertEquals(null to InstallSource.Custom, resolvePiStartup(unnamed, bundled).pair())
    }

    @Test
    fun `the user's entries are added to the retained host environment`() {
        val settings = LaunchSettings(environment = listOf(EnvironmentEntry("HTTPS_PROXY", "http://proxy:3128")))
        val environment = mutableMapOf("PATH" to "/bin", "OPENAI_API_KEY" to "sk", "CLAUDE_CONFIG_DIR" to "/c")

        applyPiEnvironment(environment, resolvePiStartup(LaunchContext(settings), bundled))

        assertEquals(mapOf("PATH" to "/bin", "HTTPS_PROXY" to "http://proxy:3128"), environment)
    }

    @Test
    fun `a newer release is installed like the bundled archive`() = runTest {
        val release = GitHubRelease(
            "v1.0.0",
            assets = listOf(
                GitHubAsset("pi-darwin-arm64.tar.gz", 31_004_152, "$download/pi-darwin-arm64.tar.gz", "sha256:$digest"),
                GitHubAsset("pi-windows-x64.zip", 45_041_072, "$download/pi-windows-x64.zip", "sha256:$digest"),
            ),
        )

        val mac = piInstallPlan(release, "darwin-arm64", Feeds())
        assertEquals("1.0.0", mac.version)
        assertEquals(ArchiveKind.TarGz(stripComponents = 1), mac.archive)
        assertEquals("pi", mac.executable)
        assertEquals(digest, mac.sha256)

        val windows = piInstallPlan(release, "windows-x64", Feeds())
        assertEquals(ArchiveKind.Zip(), windows.archive)
        assertEquals("pi.exe", windows.executable)
    }

    @Test
    fun `an archive without a digest is checked against the release checksum list`() = runTest {
        val release = GitHubRelease(
            "v1.0.0",
            assets = listOf(
                GitHubAsset("pi-darwin-x64.tar.gz", 1, "$download/pi-darwin-x64.tar.gz"),
                GitHubAsset("SHA256SUMS", 822, "$download/SHA256SUMS"),
            ),
        )
        val feeds = Feeds(mapOf("$download/SHA256SUMS" to "$digest  pi-darwin-x64.tar.gz\n"))

        assertEquals(digest, piInstallPlan(release, "darwin-x64", feeds).sha256)
        val error = assertFailsWith<ManagementException> {
            piInstallPlan(release.copy(assets = release.assets.take(1)), "darwin-x64", Feeds())
        }
        assertEquals(ManagementFailure.Install(InstallFailureReason.UntrustedSource), error.failure)
    }

    @Test
    fun `only the bundled version is verified`() {
        assertEquals(Compatibility.Verified, piCompatibility("0.87.1", "0.87.1"))
        assertEquals(Compatibility.Unverified, piCompatibility("1.0.0", "0.87.1"))
        assertEquals(Compatibility.Unknown, piCompatibility("1.0.0", null))
    }

    @Test
    fun `release targets and versions`() {
        assertEquals("darwin-arm64", piReleaseTarget("Mac OS X", "aarch64"))
        assertEquals("windows-x64", piReleaseTarget("Windows 11", "amd64"))
        assertNull(piReleaseTarget("Linux", "amd64"))
        assertEquals("1.0.0", parsePiVersion("1.0.0\n"))
        assertNull(parsePiVersion("unknown option"))
    }

    private fun PiStartup.pair() = executable to source

    private class Feeds(private val documents: Map<String, String> = emptyMap()) : ReleaseFeeds {
        override suspend fun latestGitHubRelease(owner: String, repository: String): GitHubRelease = error("unused")
        override suspend fun document(url: String, allowedHosts: Set<String>): String = documents.getValue(url)
    }
}
