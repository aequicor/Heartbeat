package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.searchengine.api.SearchException
import io.aequicor.heartbeat.feature.searchengine.api.SearchFailure
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
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
        assertEquals(SearchFailure.InvalidInput, failure { web.fetch("https://example.com/a") })
    }

    @Test fun `private addresses are refused before any request`() = runTest {
        var requested = false
        val web = PiNativeWeb(
            client {
                requested = true
                respond("", HttpStatusCode.OK)
            },
        )
        assertEquals(SearchFailure.InvalidInput, failure { web.fetch("http://192.168.1.1/router") })
        assertFalse(requested)
    }

    @Test fun `error statuses and unreadable bodies fail explicitly`() = runTest {
        val missing = PiNativeWeb(client { respond("nope", HttpStatusCode.NotFound) })
        assertEquals(SearchFailure.Unavailable, failure { missing.fetch("https://example.com/a") })

        val binary = PiNativeWeb(
            client { respond("pixels", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png")) },
        )
        assertEquals(SearchFailure.InvalidResponse, failure { binary.fetch("https://example.com/a.png") })

        val empty = PiNativeWeb(
            client { respond("  ", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain")) },
        )
        assertEquals(SearchFailure.InvalidResponse, failure { empty.fetch("https://example.com/a") })
    }

    @Test fun `entities are decoded once and unknown names survive`() = runTest {
        assertEquals("a & b < c > d \"e\" f", decodeHtmlEntities("a &amp; b &lt; c &gt; d &quot;e&quot; f"))
        assertEquals("one two", decodeHtmlEntities("one&nbsp;two"))
        assertEquals("\u2014", decodeHtmlEntities("&#8212;"))
        assertEquals("\u2014", decodeHtmlEntities("&#x2014;"))
        assertEquals("&unknown;", decodeHtmlEntities("&unknown;"))
        assertEquals("&amp;", decodeHtmlEntities("&amp;amp;"))
    }

    private suspend fun failure(block: suspend () -> Unit): SearchFailure =
        assertFailsWith<SearchException> { block() }.failure

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
