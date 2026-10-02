package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigOverride
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ArchiveKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.GitHubAsset
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.GitHubRelease
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ReleaseFeeds
import io.aequicor.heartbeat.feature.aiengine.facade.api.validateLaunchSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CodexManagementTest {
    private val target = CodexTarget("aarch64-apple-darwin", "bin/codex")
    private val digest = "07".repeat(32)
    private val download = "https://github.com/openai/codex/releases/download/rust-v0.160.0"

    @Test
    fun `the official package is installed with GitHub's checksum`() = runTest {
        val release = release(
            GitHubAsset(
                "codex-package-aarch64-apple-darwin.tar.gz",
                129_976_298,
                "$download/p.tar.gz",
                "sha256:$digest",
            ),
        )

        val plan = codexInstallPlan(release, target, Feeds())

        assertEquals("0.160.0", plan.version)
        assertEquals("$download/p.tar.gz", plan.url)
        assertEquals(digest, plan.sha256)
        assertEquals(129_976_298, plan.size)
        assertEquals(ArchiveKind.TarGz(), plan.archive)
        assertEquals("bin/codex", plan.executable)
    }

    @Test
    fun `a package without a digest falls back to the release checksum list`() = runTest {
        val sums = GitHubAsset("codex-package_SHA256SUMS", 1631, "$download/sums")
        val release = release(GitHubAsset("codex-package-aarch64-apple-darwin.tar.gz", 1, "$download/p.tar.gz"), sums)
        val feeds = Feeds(mapOf("$download/sums" to "$digest  codex-package-aarch64-apple-darwin.tar.gz\n"))

        assertEquals(digest, codexInstallPlan(release, target, feeds).sha256)
    }

    @Test
    fun `a host without a package or a package without a checksum cannot be installed`() = runTest {
        assertInstall(InstallFailureReason.NoAssetForPlatform) {
            codexInstallPlan(release(), target, Feeds())
        }
        assertInstall(InstallFailureReason.UntrustedSource) {
            codexInstallPlan(
                release(GitHubAsset("codex-package-aarch64-apple-darwin.tar.gz", 1, "$download/p")),
                target,
                Feeds(),
            )
        }
        assertInstall(InstallFailureReason.NoAssetForPlatform) {
            CodexManager(NoTargetTransport).resolveRelease(Feeds())
        }
    }

    @Test
    fun `launch settings keep Heartbeat's isolation keys reserved`() {
        val settings = LaunchSettings(
            configOverrides = listOf(
                ConfigOverride("features.web_search", "true"),
                ConfigOverride("approval_policy", "\"never\""),
                ConfigOverride("model_reasoning_summary", "\"auto\""),
            ),
        )

        val problems = validateLaunchSettings(settings, CodexManagementSpec.launch, EnginePlatform.DesktopMacOs)

        assertEquals(listOf(0, 1), problems.map { it.index })
    }

    private fun release(vararg assets: GitHubAsset) = GitHubRelease("rust-v0.160.0", assets = assets.toList())

    private suspend fun assertInstall(reason: InstallFailureReason, block: suspend () -> Unit) {
        val error = assertFailsWith<ManagementException> { block() }
        assertEquals(ManagementFailure.Install(reason), error.failure)
    }

    private class Feeds(private val documents: Map<String, String> = emptyMap()) : ReleaseFeeds {
        override suspend fun latestGitHubRelease(owner: String, repository: String): GitHubRelease = error("unused")
        override suspend fun document(url: String, allowedHosts: Set<String>): String = documents.getValue(url)
    }

    private object NoTargetTransport : CodexTransport {
        override suspend fun open(): CodexWire = error("unused")
        override suspend fun available(): EngineAvailability = error("unused")
    }
}
