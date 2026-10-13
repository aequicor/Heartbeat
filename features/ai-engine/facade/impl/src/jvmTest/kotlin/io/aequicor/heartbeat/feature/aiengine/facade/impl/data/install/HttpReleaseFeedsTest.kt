package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.install

import io.aequicor.heartbeat.core.common.release.GitHubRelease
import io.aequicor.heartbeat.core.network.release.ReleaseException
import io.aequicor.heartbeat.core.network.release.ReleaseFailure
import io.aequicor.heartbeat.core.network.release.ReleaseSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallFailureReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class HttpReleaseFeedsTest {
    @Test
    fun `metadata and custom publisher hosts reach the shared source`() = runTest {
        val release = GitHubRelease("v1.0.0")
        val source = object : ReleaseSource {
            override suspend fun latestGitHubRelease(owner: String, repository: String): GitHubRelease {
                assertEquals("openai", owner)
                assertEquals("codex", repository)
                return release
            }
            override suspend fun document(url: String, allowedHosts: Set<String>, maxBytes: Int): String {
                assertEquals("https://downloads.claude.ai/stable", url)
                assertEquals(setOf("downloads.claude.ai"), allowedHosts)
                return "1.2.3"
            }
        }
        val feeds = HttpReleaseFeeds(source)
        assertSame(release, feeds.latestGitHubRelease("openai", "codex"))
        assertEquals("1.2.3", feeds.document("https://downloads.claude.ai/stable", setOf("downloads.claude.ai")))
    }

    @Test
    fun `draft and prerelease remain no release`() = runTest {
        listOf(GitHubRelease("v1", isDraft = true), GitHubRelease("v1", isPrerelease = true)).forEach { release ->
            assertInstallFailure(InstallFailureReason.NoRelease) {
                HttpReleaseFeeds(source { release }).latestGitHubRelease("o", "r")
            }
        }
    }

    @Test
    fun `all shared failures map to installation failures`() = runTest {
        val cases = listOf(
            ReleaseException(ReleaseFailure.Http, 403) to InstallFailureReason.RateLimited,
            ReleaseException(ReleaseFailure.Http, 429) to InstallFailureReason.RateLimited,
            ReleaseException(ReleaseFailure.Http, 404) to InstallFailureReason.NoRelease,
            ReleaseException(ReleaseFailure.Http, 410) to InstallFailureReason.NoRelease,
            ReleaseException(ReleaseFailure.Http, 502) to InstallFailureReason.Network,
            ReleaseException(ReleaseFailure.Network) to InstallFailureReason.Network,
            ReleaseException(ReleaseFailure.InvalidMetadata) to InstallFailureReason.NoRelease,
            ReleaseException(ReleaseFailure.UntrustedSource) to InstallFailureReason.UntrustedSource,
            ReleaseException(ReleaseFailure.TooLarge) to InstallFailureReason.TooLarge,
            ReleaseException(ReleaseFailure.SizeMismatch) to InstallFailureReason.SizeMismatch,
            ReleaseException(ReleaseFailure.ChecksumMismatch) to InstallFailureReason.ChecksumMismatch,
            ReleaseException(ReleaseFailure.NotEnoughSpace) to InstallFailureReason.NotEnoughSpace,
            ReleaseException(ReleaseFailure.Storage) to InstallFailureReason.Storage,
        )
        cases.forEach { (failure, reason) ->
            assertInstallFailure(reason) { HttpReleaseFeeds(source { throw failure }).latestGitHubRelease("o", "r") }
        }
    }

    @Test
    fun `cancellation is not mapped`() = runTest {
        val cancellation = CancellationException("cancel")
        assertSame(
            cancellation,
            assertFailsWith<CancellationException> {
                HttpReleaseFeeds(source { throw cancellation }).latestGitHubRelease("o", "r")
            },
        )
    }

    private fun source(latest: () -> GitHubRelease): ReleaseSource = object : ReleaseSource {
        override suspend fun latestGitHubRelease(owner: String, repository: String) = latest()
        override suspend fun document(url: String, allowedHosts: Set<String>, maxBytes: Int): String = error("unused")
    }
}
