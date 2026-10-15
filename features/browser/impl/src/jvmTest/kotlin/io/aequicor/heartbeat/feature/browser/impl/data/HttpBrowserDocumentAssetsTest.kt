package io.aequicor.heartbeat.feature.browser.impl.data

import io.aequicor.heartbeat.core.network.SensitiveNetworkRequest
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserDocumentAssetResult.Content
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserDocumentAssetResult.Redirect
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class HttpBrowserDocumentAssetsTest {
    @Test
    fun `loads bytes and preserves MIME charset and actual CORS without forwarding cookies`() = runTest {
        documentAssets {
            respond(
                "body { color: red }",
                headers = headersOf(
                    HttpHeaders.ContentType to listOf("text/css; charset=utf-8"),
                    HttpHeaders.AccessControlAllowOrigin to listOf("https://allowed.example"),
                    HttpHeaders.AccessControlAllowCredentials to listOf("true"),
                    "Cross-Origin-Resource-Policy" to listOf("cross-origin"),
                    HttpHeaders.SetCookie to listOf("session=secret"),
                    HttpHeaders.Location to listOf("https://private.example/secret"),
                ),
            )
        }.use { fixture ->
            val content = assertIs<Content>(fixture.assets.open().load(ASSET_URL))
            assertContentEquals("body { color: red }".encodeToByteArray(), content.body)
            assertEquals("text/css", content.mimeType)
            assertEquals("text/css; charset=utf-8", content.headers[HttpHeaders.ContentType])
            assertEquals("https://allowed.example", content.headers[HttpHeaders.AccessControlAllowOrigin])
            assertEquals("true", content.headers[HttpHeaders.AccessControlAllowCredentials])
            assertEquals("cross-origin", content.headers["Cross-Origin-Resource-Policy"])
            assertEquals(4, content.headers.size)
            assertFalse(content.toString().contains("color"))
            assertFalse(content.toString().contains("allowed"))
        }
    }

    @Test
    fun `strips inherited headers cookies and URL defaults at the final sending boundary`() = runTest {
        val cookies = RecordingCookies()
        documentAssets(
            configure = {
                defaultRequest {
                    url("https://private.example/default?inherited=secret")
                    headers.append(HttpHeaders.Authorization, "Bearer secret")
                    headers.append(HttpHeaders.Cookie, "private=secret")
                    headers.append(HttpHeaders.Referrer, "https://private.example/secret")
                    headers.append("X-Private", "secret")
                }
                install(HttpCookies) { storage = cookies }
            },
        ) { request ->
            if (request.attributes.contains(SensitiveNetworkRequest)) {
                assertEquals(ASSET_URL, request.url.toString())
                assertTrue(request.headers.isEmpty())
            }
            respond("ok", headers = headersOf(HttpHeaders.SetCookie, "asset=secret"))
        }.use { fixture ->
            assertIs<Content>(fixture.assets.open().load(ASSET_URL))
            assertEquals(0, cookies.reads)
            assertEquals(0, cookies.writes)
            fixture.profile.close()
            assertFalse(cookies.isClosed)
            assertNull(fixture.assets.open().load(ASSET_URL))
            assertEquals(HttpStatusCode.OK, fixture.client.get(ASSET_URL).status)
            assertTrue(cookies.reads > 0)
            assertTrue(cookies.writes > 0)
        }
    }

    @Test
    fun `returns one redirect hop resolved against the requested URL`() = runTest {
        documentAssets {
            respond(
                "",
                HttpStatusCode.Found,
                headersOf(
                    HttpHeaders.Location to listOf("../v2/main.css?version=2"),
                    HttpHeaders.AccessControlAllowOrigin to listOf("*"),
                    "Cross-Origin-Resource-Policy" to listOf("cross-origin"),
                    HttpHeaders.SetCookie to listOf("private=secret"),
                ),
            )
        }.use { fixture ->
            val redirect = assertIs<Redirect>(fixture.assets.open().load(ASSET_URL))
            assertEquals("https://assets.example/v2/main.css?version=2", redirect.url)
            assertEquals(1, fixture.engine.requestHistory.size)
            assertEquals(
                mapOf(HttpHeaders.AccessControlAllowOrigin to "*", "Cross-Origin-Resource-Policy" to "cross-origin"),
                redirect.headers,
            )
            assertEquals("Redirect(url=redacted, metadata=redacted)", redirect.toString())
        }
    }

    @Test
    fun `refuses non HTTP credentials malformed URLs and unsafe redirect destinations`() = runTest {
        val invalid = listOf(
            "file:///tmp/private", "data:text/plain,secret", "javascript:alert(1)", "/relative.css",
            "https:///missing-host", "https://user:password@assets.example/x", "https://assets.example:99999/x",
            "https://assets.example/\nsecret", "https://assets.example/%zz",
            "https://assets.example/" + "x".repeat(
                8192,
            ),
        )
        documentAssets { respond("ok") }.use { fixture ->
            val session = fixture.assets.open()
            invalid.forEach { assertNull(session.load(it)) }
            assertEquals(0, fixture.engine.requestHistory.size)
        }
        invalid.filterNot { it.startsWith('/') || '\n' in it }.forEach { destination ->
            documentAssets {
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, destination))
            }.use { fixture ->
                assertNull(fixture.assets.open().load(ASSET_URL))
                assertEquals(1, fixture.engine.requestHistory.size)
            }
        }
    }

    @Test
    fun `does not synthesize CORS or pass unbounded and repeated response declarations`() = runTest {
        documentAssets {
            respond(
                "ok",
                headers = headersOf(
                    HttpHeaders.AccessControlAllowOrigin to listOf("x".repeat(2049)),
                    HttpHeaders.AccessControlAllowCredentials to listOf("true", "false"),
                    "X-Private" to listOf("secret"),
                ),
            )
        }.use { fixture ->
            val content = assertIs<Content>(fixture.assets.open().load(ASSET_URL))
            assertEquals(mapOf(HttpHeaders.ContentType to "application/octet-stream"), content.headers)
        }
    }

    @Test
    fun `refuses malformed MIME and undecoded encoding without exposing a body`() = runTest {
        listOf(
            headersOf(HttpHeaders.ContentType, "not-a-mime"),
            headersOf(HttpHeaders.ContentEncoding, "gzip"),
            headersOf(HttpHeaders.ContentType, "text/css;" + "x".repeat(2048)),
        ).forEach { headers ->
            documentAssets { respond("secret", headers = headers) }.use { fixture ->
                assertNull(fixture.assets.open().load(ASSET_URL))
            }
        }
    }
}

private class RecordingCookies : CookiesStorage {
    var reads = 0
    var writes = 0
    var isClosed = false
    override suspend fun get(requestUrl: Url): List<Cookie> {
        reads++
        return listOf(Cookie("session", "secret"))
    }
    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) {
        writes++
    }
    override fun close() {
        isClosed = true
    }
}
