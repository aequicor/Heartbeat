package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.searchengine.api.SearchException
import io.aequicor.heartbeat.feature.searchengine.api.SearchFailure
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PiNativeWebTest {
    @Test fun `html page is reduced to titled text`() = runTest {
        val web = PiNativeWeb(
            client { respond(PAGE, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8")) },
        )
        val page = web.fetch("https://example.com/a")
        assertEquals("https://example.com/a", page.url)
        assertEquals("Example & Friends", page.title)
        assertTrue("Hello World" in page.text, page.text)
        assertTrue("Line—one" in page.text, page.text)
        assertTrue("two" in page.text, page.text)
        assertTrue("secret" !in page.text, page.text)
        assertTrue("color: red" !in page.text, page.text)
    }

    @Test fun `plain text passes through without a title`() = runTest {
        val web = PiNativeWeb(
            client { respond("Just text", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain")) },
        )
        val page = web.fetch("https://example.com/a.txt")
        assertEquals("Just text", page.text)
        assertNull(page.title)
    }

    @Test fun `redirects are followed to the final public url`() = runTest {
        val web = PiNativeWeb(
            client { request ->
                if (request.url.encodedPath == "/start") {
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/final"))
                } else {
                    respond("Final text", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
                }
            },
        )
        val page = web.fetch("https://example.com/start")
        assertEquals("https://example.com/final", page.url)
        assertEquals("Final text", page.text)
    }

    @Test fun `a redirect into the local network fails the read`() = runTest {
        val web = PiNativeWeb(
            client { respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "http://127.0.0.1/admin")) },
        )
        val error = failing { web.fetch("https://example.com/a") }
        assertEquals(SearchFailure.InvalidInput, error.failure)
        assertEquals(
            "invalid_url — url=http://127.0.0.1/admin — redirect target is not a public http(s) address",
            error.details,
        )
    }

    @Test fun `private addresses are refused before any request`() = runTest {
        var isRequested = false
        val web = PiNativeWeb(
            client {
                isRequested = true
                respond("", HttpStatusCode.OK)
            },
        )
        assertEquals(SearchFailure.InvalidInput, failure { web.fetch("http://192.168.1.1/router") })
        assertFalse(isRequested)
    }

    @Test fun `error statuses and unreadable bodies fail explicitly`() = runTest {
        val missing = PiNativeWeb(client { respond("nope", HttpStatusCode.NotFound) })
        val notFound = failing { missing.fetch("https://example.com/a") }
        assertEquals(SearchFailure.Unavailable, notFound.failure)
        assertEquals("http_error 404 Not Found — url=https://example.com/a — body: \"nope\"", notFound.details)

        val binary = PiNativeWeb(
            client { respond("pixels", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png")) },
        )
        val unreadable = failing { binary.fetch("https://example.com/a.png") }
        assertEquals(SearchFailure.InvalidResponse, unreadable.failure)
        assertEquals(
            "unsupported_content_type — url=https://example.com/a.png — content-type=image/png",
            unreadable.details,
        )

        val empty = PiNativeWeb(
            client { respond("  ", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain")) },
        )
        val blank = failing { empty.fetch("https://example.com/a") }
        assertEquals(SearchFailure.InvalidResponse, blank.failure)
        assertEquals("empty_response — url=https://example.com/a — status 200 OK", blank.details)
    }

    @Test fun `auth and rate limit statuses keep their own failure`() = runTest {
        val denied = PiNativeWeb(
            client { respond("denied", HttpStatusCode.Forbidden, headersOf(HttpHeaders.ContentType, "text/plain")) },
        )
        val forbidden = failing { denied.fetch("https://example.com/private") }
        assertEquals(SearchFailure.Authentication, forbidden.failure)
        assertEquals(
            "http_error 403 Forbidden — url=https://example.com/private — body: \"denied\"",
            forbidden.details,
        )

        val limited = PiNativeWeb(client { respond("slow down", HttpStatusCode.TooManyRequests) })
        val rate = failing { limited.fetch("https://example.com/a") }
        assertEquals(SearchFailure.RateLimited, rate.failure)
        assertEquals(
            "http_error 429 Too Many Requests — url=https://example.com/a — body: \"slow down\"",
            rate.details,
        )
    }

    @Test fun `binary error bodies are reported by type and size only`() = runTest {
        val web = PiNativeWeb(
            client {
                respond(
                    "pixels",
                    HttpStatusCode.ServiceUnavailable,
                    headersOf(HttpHeaders.ContentType to listOf("image/png"), HttpHeaders.ContentLength to listOf("6")),
                )
            },
        )
        val error = failing { web.fetch("https://example.com/a.png") }
        assertEquals(SearchFailure.Unavailable, error.failure)
        assertEquals(
            "http_error 503 Service Unavailable — url=https://example.com/a.png — " +
                "body omitted: binary content-type image/png, 6 bytes",
            error.details,
        )
    }

    @Test fun `transport failures name their category and url`() = runTest {
        val timedOut = PiNativeWeb(client { throw ConnectTimeoutException("timed out") })
        val timeout = failing { timedOut.fetch("https://example.com/a") }
        assertEquals(SearchFailure.Timeout, timeout.failure)
        assertEquals("timeout — url=https://example.com/a — cause: ConnectTimeoutException", timeout.details)

        val offline = PiNativeWeb(client { throw IOException("no route") })
        val network = failing { offline.fetch("https://example.com/a") }
        assertEquals(SearchFailure.Connectivity, network.failure)
        assertEquals("network_error — url=https://example.com/a — cause: IOException", network.details)
    }

    @Test fun `redirect loops are reported instead of followed forever`() = runTest {
        var hops = 0
        val web = PiNativeWeb(
            client { request ->
                hops++
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, request.url.encodedPath))
            },
        )
        val loop = failing { web.fetch("https://example.com/a") }
        assertEquals(SearchFailure.Unavailable, loop.failure)
        assertEquals(
            "redirect_loop — url=https://example.com/a — stopped after 5 redirects at https://example.com/a " +
                "(302 Found)",
            loop.details,
        )
        assertEquals(6, hops)
    }

    @Test fun `redirects without a location header are invalid redirects`() = runTest {
        val web = PiNativeWeb(client { respond("", HttpStatusCode.Found) })
        val invalid = failing { web.fetch("https://example.com/a") }
        assertEquals(SearchFailure.InvalidResponse, invalid.failure)
        assertEquals(
            "invalid_redirect — url=https://example.com/a — 302 Found without a Location header",
            invalid.details,
        )
    }

    @Test fun `oversized responses report the limit`() = runTest {
        val web = PiNativeWeb(
            client {
                respond("x".repeat(2_000_001), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
            },
        )
        val large = failing { web.fetch("https://example.com/a") }
        assertEquals(SearchFailure.InvalidResponse, large.failure)
        assertEquals(
            "too_large — url=https://example.com/a — size: 2000001 chars, limit: 2000000 chars",
            large.details,
        )
    }

    @Test fun `entities are decoded once and unknown names survive`() = runTest {
        assertEquals("a & b < c > d \"e\" f", decodeHtmlEntities("a &amp; b &lt; c &gt; d &quot;e&quot; f"))
        assertEquals("one two", decodeHtmlEntities("one&nbsp;two"))
        assertEquals("\u2014", decodeHtmlEntities("&#8212;"))
        assertEquals("\u2014", decodeHtmlEntities("&#x2014;"))
        assertEquals("&unknown;", decodeHtmlEntities("&unknown;"))
        assertEquals("&amp;", decodeHtmlEntities("&amp;amp;"))
    }

    private suspend fun failing(block: suspend () -> Unit): SearchException =
        assertFailsWith<SearchException> { block() }

    private suspend fun failure(block: suspend () -> Unit): SearchFailure = failing(block).failure

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        HttpClient(MockEngine(handler))

    private companion object {
        val PAGE = """
            <html>
              <head>
                <title>Example &amp; Friends</title>
                <style>body { color: red }</style>
              </head>
              <body>
                <script>var secret = 1;</script>
                <h1>Hello&nbsp;World</h1>
                <p>Line&#8212;one<br>two</p>
              </body>
            </html>
        """.trimIndent()
    }
}
