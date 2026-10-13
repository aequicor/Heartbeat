package io.aequicor.heartbeat.core.network.impl.release

import io.aequicor.heartbeat.core.network.release.ReleaseException
import io.aequicor.heartbeat.core.network.release.ReleaseFailure
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class HttpReleaseSourceTest {
    @Test
    fun `error bodies are not buffered by the application client`() = runTest {
        val body = io.ktor.utils.io.ByteChannel()
        val engine = io.ktor.client.engine.mock.MockEngine {
            respond(body, HttpStatusCode.Forbidden)
        }
        val client = io.aequicor.heartbeat.core.network.impl.createHttpClient(
            engine,
            io.aequicor.heartbeat.core.network.NetworkConfig(maxRetries = 0),
        )
        try {
            val source = HttpReleaseSource(client, dispatchers())
            val failure = assertFailsWith<ReleaseException> {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    kotlinx.coroutines.withTimeout(5_000) { source.latestGitHubRelease("owner", "repo") }
                }
            }
            assertEquals(403, failure.status)
        } finally {
            body.cancel(kotlinx.coroutines.CancellationException("test complete"))
            client.close()
        }
    }

    @Test
    fun `metadata preserves flags page and digest without credentials`() = runTest {
        val client = releaseClient { request ->
            assertEquals("https://api.github.com/repos/owner/repo/releases/latest", request.url.toString())
            assertNull(request.headers[HttpHeaders.Authorization])
            assertNull(request.headers[HttpHeaders.Cookie])
            respond(
                """{"tag_name":"v1.2.3","draft":true,"prerelease":true,"html_url":"https://github.com/page",
                "assets":[{"name":"build","browser_download_url":"https://github.com/build",
                "digest":"sha256:${"ab".repeat(
                    32,
                )}"}]}""",
                HttpStatusCode.OK,
            )
        }
        val release = HttpReleaseSource(client, dispatchers()).latestGitHubRelease("owner", "repo")
        assertEquals("v1.2.3", release.tag)
        assertEquals(true, release.isDraft)
        assertEquals(true, release.isPrerelease)
        assertEquals("https://github.com/page", release.pageUrl)
        assertEquals("ab".repeat(32), release.asset("build")?.sha256)
    }

    @Test
    fun `HTTP status survives for different consumer policies`() = runTest {
        listOf(
            HttpStatusCode.NotFound,
            HttpStatusCode.Gone,
            HttpStatusCode.Forbidden,
            HttpStatusCode.TooManyRequests,
            HttpStatusCode.BadGateway,
        ).forEach { status ->
            val source = HttpReleaseSource(releaseClient { respond("private body", status) }, dispatchers())
            val failure = assertFailsWith<ReleaseException> { source.latestGitHubRelease("owner", "repo") }
            assertEquals(ReleaseFailure.Http, failure.failure)
            assertEquals(status.value, failure.status)
            assertNull(failure.cause)
        }
    }

    @Test
    fun `invalid metadata is sanitized`() = runTest {
        val source = HttpReleaseSource(releaseClient { respond("private body", HttpStatusCode.OK) }, dispatchers())
        val failure = assertFailsWith<ReleaseException> { source.latestGitHubRelease("owner", "repo") }
        assertEquals(ReleaseFailure.InvalidMetadata, failure.failure)
        kotlin.test.assertTrue(failure.message.orEmpty().startsWith("InvalidMetadata"))
        assertNull(failure.cause)
    }

    @Test
    fun `documents are bounded and trusted before and after redirects`() = runTest {
        val hosts = setOf("downloads.claude.ai")
        val url = "https://downloads.claude.ai/stable"
        val source = HttpReleaseSource(releaseClient { respond("12345", HttpStatusCode.OK) }, dispatchers())
        assertEquals("12345", source.document(url, hosts, 5))
        assertReleaseFailure(ReleaseFailure.TooLarge) { source.document(url, hosts, 4) }
        assertReleaseFailure(ReleaseFailure.UntrustedSource) { source.document("http://downloads.claude.ai/a", hosts) }
        assertReleaseFailure(ReleaseFailure.UntrustedSource) { source.document("https://evil.test/a", hosts) }
        val redirect = HttpReleaseSource(
            releaseClient { request ->
                if (request.url.host == "downloads.claude.ai") {
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://evil.test/private"))
                } else {
                    respond("12345", HttpStatusCode.OK)
                }
            },
            dispatchers(),
        )
        assertReleaseFailure(ReleaseFailure.UntrustedSource) { redirect.document(url, hosts) }
    }
}
