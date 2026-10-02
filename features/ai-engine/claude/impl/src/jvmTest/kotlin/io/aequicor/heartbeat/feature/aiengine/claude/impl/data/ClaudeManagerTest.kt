package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ArchiveKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.GitHubRelease
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ReleaseFeeds
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ClaudeManagerTest {
    private val base = "https://downloads.claude.ai/claude-code-releases"
    private val digest = "5a".repeat(32)

    @Test
    fun `the stable build is installed with the manifest's checksum and size`() = runTest {
        val feeds = Feeds(manifest(platform = "darwin-arm64", binary = "claude"))

        val plan = claudeInstallPlan(feeds, "darwin-arm64")

        assertEquals("2.1.285", plan.version)
        assertEquals("$base/2.1.285/darwin-arm64/claude", plan.url)
        assertEquals(digest, plan.sha256)
        assertEquals(223_821_616, plan.size)
        assertEquals(ArchiveKind.Raw("claude"), plan.archive)
        assertEquals("claude", plan.executable)
        assertEquals(setOf("downloads.claude.ai"), plan.allowedHosts)
        assertEquals(setOf("downloads.claude.ai"), feeds.hosts)
    }

    @Test
    fun `windows installs claude exe`() = runTest {
        val plan = claudeInstallPlan(Feeds(manifest(platform = "win32-x64", binary = "claude.exe")), "win32-x64")

        assertEquals("$base/2.1.285/win32-x64/claude.exe", plan.url)
        assertEquals("claude.exe", plan.executable)
    }

    @Test
    fun `an unexpected channel or manifest is never installed`() = runTest {
        assertInstall(InstallFailureReason.NoRelease) {
            claudeInstallPlan(Feeds(manifest(), stable = "<html>unavailable</html>"), "darwin-arm64")
        }
        assertInstall(InstallFailureReason.NoRelease) { claudeInstallPlan(Feeds("not json"), "darwin-arm64") }
        assertInstall(InstallFailureReason.NoAssetForPlatform) {
            claudeInstallPlan(Feeds(manifest()), "win32-arm64")
        }
        assertInstall(InstallFailureReason.UntrustedSource) {
            claudeInstallPlan(Feeds(manifest(version = "2.1.284")), "darwin-arm64")
        }
        assertInstall(InstallFailureReason.UntrustedSource) {
            claudeInstallPlan(Feeds(manifest(binary = "../claude")), "darwin-arm64")
        }
        assertInstall(InstallFailureReason.UntrustedSource) {
            claudeInstallPlan(Feeds(manifest(checksum = "abc")), "darwin-arm64")
        }
    }

    private fun manifest(
        version: String = "2.1.285",
        platform: String = "darwin-arm64",
        binary: String = "claude",
        checksum: String = digest,
    ) = """
        {"version":"$version","buildDate":"2026-09-29T01:45:50Z","platforms":{
          "$platform":{"binary":"$binary","checksum":"$checksum","size":223821616},
          "linux-x64":{"binary":"claude","checksum":"$digest","size":1}
        }}
        """.trimIndent()

    private suspend fun assertInstall(reason: InstallFailureReason, block: suspend () -> Unit) {
        val error = assertFailsWith<ManagementException> { block() }
        assertEquals(ManagementFailure.Install(reason), error.failure)
    }

    private inner class Feeds(private val manifest: String, private val stable: String = "2.1.285\n") : ReleaseFeeds {
        val hosts = mutableSetOf<String>()

        override suspend fun latestGitHubRelease(owner: String, repository: String): GitHubRelease = error("unused")

        override suspend fun document(url: String, allowedHosts: Set<String>): String {
            hosts += allowedHosts
            return when (url) {
                "$base/stable" -> stable
                "$base/${stable.trim()}/manifest.json" -> manifest
                else -> error("Unexpected document")
            }
        }
    }
}
