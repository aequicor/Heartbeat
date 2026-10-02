package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.install

import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallFailureReason
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HttpReleaseFeedsTest {
    private val release = """
        {"tag_name": "rust-v0.159.3", "draft": false, "prerelease": false, "assets": [
          {"name": "codex-package-aarch64-apple-darwin.tar.gz", "size": 42,
           "browser_download_url": "https://github.com/openai/codex/releases/download/rust-v0.159.3/a.tar.gz",
           "digest": "sha256:${"ab".repeat(32)}"}
        ]}
    """.trimIndent()

    @Test
    fun `the latest GitHub release is read from the API`() = runTest {
        val requested = mutableListOf<String>()
        val feeds = HttpReleaseFeeds(
            releaseClient { request ->
                requested += request.url.toString()
                respond(release, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )

        val latest = feeds.latestGitHubRelease("openai", "codex")

        assertEquals("rust-v0.159.3", latest.tag)
        assertEquals("ab".repeat(32), latest.assets.single().sha256)
        assertEquals(listOf("https://api.github.com/repos/openai/codex/releases/latest"), requested)
    }

    @Test
    fun `drafts, pre-releases and missing releases are no release`() = runTest {
        listOf(
            release.replace("\"draft\": false", "\"draft\": true"),
            release.replace("\"prerelease\": false", "\"prerelease\": true"),
            "not json",
        ).forEach { body ->
            assertInstallFailure(InstallFailureReason.NoRelease) {
                HttpReleaseFeeds(
                    releaseClient { respond(body, HttpStatusCode.OK) },
                ).latestGitHubRelease("openai", "codex")
            }
        }
        assertInstallFailure(InstallFailureReason.NoRelease) {
            HttpReleaseFeeds(
                releaseClient { respond("", HttpStatusCode.NotFound) },
            ).latestGitHubRelease("openai", "codex")
        }
        assertInstallFailure(InstallFailureReason.RateLimited) {
            HttpReleaseFeeds(
                releaseClient { respond("", HttpStatusCode.Forbidden) },
            ).latestGitHubRelease("openai", "codex")
        }
    }

    @Test
    fun `documents come only over https from the named hosts and stay small`() = runTest {
        val stable = "https://downloads.claude.ai/claude-code-releases/stable"
        val hosts = setOf("downloads.claude.ai")
        val feeds = HttpReleaseFeeds(releaseClient { respond("2.1.285\n", HttpStatusCode.OK) }, maxBytes = 16)

        assertEquals("2.1.285\n", feeds.document(stable, hosts))
        assertInstallFailure(InstallFailureReason.UntrustedSource) {
            feeds.document(stable.replace("https", "http"), hosts)
        }
        assertInstallFailure(InstallFailureReason.UntrustedSource) {
            feeds.document("https://example.com/stable", hosts)
        }
        assertInstallFailure(InstallFailureReason.TooLarge) {
            HttpReleaseFeeds(releaseClient { respond("x".repeat(17), HttpStatusCode.OK) }, maxBytes = 16)
                .document(stable, hosts)
        }
        assertInstallFailure(InstallFailureReason.UntrustedSource) {
            HttpReleaseFeeds(
                releaseClient { request ->
                    if (request.url.host == "downloads.claude.ai") {
                        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://example.com/x"))
                    } else {
                        respond("2.0.0", HttpStatusCode.OK)
                    }
                },
            ).document(stable, hosts)
        }
    }
}
