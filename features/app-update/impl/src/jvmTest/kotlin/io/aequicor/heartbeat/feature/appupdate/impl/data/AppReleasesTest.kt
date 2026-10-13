package io.aequicor.heartbeat.feature.appupdate.impl.data

import io.aequicor.heartbeat.feature.appupdate.api.AppRelease
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateFailure
import io.aequicor.heartbeat.feature.appupdate.api.AppVersion
import io.aequicor.heartbeat.feature.appupdate.impl.domain.GitHubRepository
import io.aequicor.heartbeat.feature.appupdate.impl.domain.ReleasePlatform
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppReleasesTest {
    private val repository = GitHubRepository("aequicor", "Heartbeat")
    private val installed = AppVersion(1, 3, 0)
    private val download = "https://github.com/aequicor/Heartbeat/releases/download/v1.4.0"
    private val digest = "ab".repeat(32)

    private fun release(tag: String = "v1.4.0", digest: String? = this.digest, extra: String = ""): String = """
        {"tag_name": "$tag", "html_url": "https://github.com/aequicor/Heartbeat/releases/tag/$tag",
         "draft": false, "prerelease": false, "assets": [
          {"name": "Heartbeat-1.4.0-macos-arm64.dmg", "size": 42,
           "browser_download_url": "$download/Heartbeat-1.4.0-macos-arm64.dmg"
           ${digest?.let { ", \"digest\": \"sha256:$it\"" }.orEmpty()}}
          $extra
        ]}
        """.trimIndent()

    private fun releases(vararg bodies: Pair<String, Pair<HttpStatusCode, String>>, requested: MutableList<String>) =
        AppReleases(
            GitHubReleaseClient(
                releaseClient { request ->
                    val url = request.url.toString()
                    requested += url
                    val (status, body) = bodies.toMap().getValue(url)
                    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
                },
            ),
        )

    @Test
    fun `a newer release is offered with GitHub's digest`() = runTest {
        val requested = mutableListOf<String>()
        val latest = "https://api.github.com/repos/aequicor/Heartbeat/releases/latest"
        val found = releases(latest to (HttpStatusCode.OK to release()), requested = requested)
            .newer(repository, installed, ReleasePlatform.MacOsArm64)

        assertEquals(
            AppRelease(
                version = "1.4.0",
                assetName = "Heartbeat-1.4.0-macos-arm64.dmg",
                url = "$download/Heartbeat-1.4.0-macos-arm64.dmg",
                sha256 = digest,
                size = 42,
                pageUrl = "https://github.com/aequicor/Heartbeat/releases/tag/v1.4.0",
            ),
            found,
        )
        assertEquals(listOf(latest), requested)
    }

    @Test
    fun `without a digest the checksum list of the release is read`() = runTest {
        val sums = "$download/SHA256SUMS"
        val hex = "cd".repeat(32)
        val found = releases(
            "https://api.github.com/repos/aequicor/Heartbeat/releases/latest" to (
                HttpStatusCode.OK to release(
                    digest = null,
                    extra = """, {"name": "SHA256SUMS", "size": 1, "browser_download_url": "$sums"}""",
                )
            ),
            sums to (HttpStatusCode.OK to "$hex  Heartbeat-1.4.0-macos-arm64.dmg\n"),
            requested = mutableListOf(),
        ).newer(repository, installed, ReleasePlatform.MacOsArm64)

        assertEquals(hex, found?.sha256)
    }

    @Test
    fun `no newer app release is no update`() = runTest {
        val latest = "https://api.github.com/repos/aequicor/Heartbeat/releases/latest"
        listOf(
            HttpStatusCode.OK to release(tag = "v1.3.0"),
            HttpStatusCode.OK to release(tag = "152.0.6-hb2"),
            HttpStatusCode.NotFound to "",
        ).forEach { answer ->
            assertNull(
                releases(latest to answer, requested = mutableListOf())
                    .newer(repository, installed, ReleasePlatform.MacOsArm64),
            )
        }
    }

    @Test
    fun `a newer release is never offered unverified or without a build`() = runTest {
        val latest = "https://api.github.com/repos/aequicor/Heartbeat/releases/latest"
        assertUpdateFailure(AppUpdateFailure.InvalidRelease) {
            releases(latest to (HttpStatusCode.OK to release(digest = null)), requested = mutableListOf())
                .newer(repository, installed, ReleasePlatform.MacOsArm64)
        }
        assertUpdateFailure(AppUpdateFailure.InvalidRelease) {
            releases(latest to (HttpStatusCode.OK to release()), requested = mutableListOf())
                .newer(repository, installed, ReleasePlatform.WindowsX64)
        }
        assertUpdateFailure(AppUpdateFailure.InvalidRelease) {
            releases(latest to (HttpStatusCode.OK to "not json"), requested = mutableListOf())
                .newer(repository, installed, ReleasePlatform.MacOsArm64)
        }
    }

    @Test
    fun `rate limits and server errors are reported`() = runTest {
        val latest = "https://api.github.com/repos/aequicor/Heartbeat/releases/latest"
        assertUpdateFailure(AppUpdateFailure.RateLimited) {
            releases(latest to (HttpStatusCode.Forbidden to ""), requested = mutableListOf())
                .newer(repository, installed, ReleasePlatform.MacOsArm64)
        }
        assertUpdateFailure(AppUpdateFailure.Network) {
            releases(latest to (HttpStatusCode.BadGateway to ""), requested = mutableListOf())
                .newer(repository, installed, ReleasePlatform.MacOsArm64)
        }
    }
}
