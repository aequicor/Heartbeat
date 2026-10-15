package io.aequicor.heartbeat.feature.browser.impl.ui

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewIssueKind
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewRequest
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewResult
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentResourcePolicy
import io.aequicor.heartbeat.feature.browser.impl.data.HttpBrowserDocumentAssets
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserDocumentAssetSession
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserDocumentAssets
import io.aequicor.heartbeat.feature.browser.impl.domain.DesktopBrowserResources
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserTestScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real host HTTP relay, CEF policy, redirect base URLs, CORS and applied resources, with only a local tripwire. */
internal fun verifyNativeDocumentAssets(
    resources: DesktopBrowserResources,
    dispatchers: DispatcherProvider,
    scope: CoroutineScope,
) {
    NativeAssetServer().use { server ->
        val owner = BrowserTestScope(
            CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job])),
        )
        val client = HttpClient(OkHttp) { install(HttpRequestRetry) }
        val assets = HttpBrowserDocumentAssets(client, dispatchers, owner)
        val fixture = NativePreviewFixture(resources, dispatchers, scope, assets)
        try {
            val html = externalAssetHtml(server.url)
            val external = fixture.render(html, 640, resourcePolicy = BrowserDocumentResourcePolicy.ExternalAssets)
            assertEquals("rgb(23, 45, 67)", external.sample("css").color)
            assertEquals(70.0, external.sample("script").width, "Classic external JavaScript must execute")
            assertEquals(71.0, external.sample("font").width, "Relative font after CSS redirect must load")
            assertEquals(72.0, external.sample("image").width, "External image must load")
            assertEquals(73.0, external.sample("module").width, "Module redirect must preserve relative import URL")
            assertEquals(42.0, external.sample("denied").width, "Relay must preserve a missing CORS permission")
            assertFalse(
                external.issues.any {
                    it.kind == BrowserDocumentPreviewIssueKind.ExternalResource &&
                        "#css" in it.element
                },
            )
            assertTrue(server.paths.containsAll(ASSET_PATHS), server.paths.toString())
            assertEquals(
                emptyList(),
                server.paths.filter { it !in ASSET_PATHS },
                "Connect/frame/worker traffic stays blocked",
            )
            assertFalse(server.hasPrivateHeaders, "Browser cookies, authorization and referrer must not reach assets")
            val count = server.paths.size
            val isolated = fixture.render(html, 640)
            assertEquals(42.0, isolated.sample("script").width)
            assertTrue(isolated.issues.any { it.kind == BrowserDocumentPreviewIssueKind.ExternalResource })
            assertEquals(count, server.paths.size, "Default isolation must not invoke the relay")
        } finally {
            fixture.close()
            owner.close()
            owner.coroutineScope.cancel()
            client.close()
        }
    }
    verifyPendingAssetCancellation(resources, dispatchers, scope)
}

private fun verifyPendingAssetCancellation(
    resources: DesktopBrowserResources,
    dispatchers: DispatcherProvider,
    scope: CoroutineScope,
) {
    val started = CompletableDeferred<Unit>()
    val cancelled = CompletableDeferred<Unit>()
    val assets = BrowserDocumentAssets {
        BrowserDocumentAssetSession {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
    }
    val fixture = NativePreviewFixture(resources, dispatchers, scope, assets)
    try {
        runBlocking {
            withTimeout(15_000) {
                val rendering = async {
                    fixture.previews.preview(
                        BrowserDocumentPreviewRequest(
                            "<script src='https://assets.example/pending.js'></script>",
                            resourcePolicy = BrowserDocumentResourcePolicy.ExternalAssets,
                        ),
                    )
                }
                started.await()
                rendering.cancelAndJoin()
                cancelled.await()
                assertTrue(fixture.documents.documents.value.isEmpty())
            }
        }
    } finally {
        fixture.close()
    }
}

private class NativeAssetServer : AutoCloseable {
    val paths = ConcurrentLinkedQueue<String>()

    @Volatile
    var hasPrivateHeaders = false
        private set
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { answer(it) }
        start()
    }
    val url = "http://127.0.0.1:${server.address.port}"

    private fun answer(exchange: HttpExchange) = exchange.use {
        val path = exchange.requestURI.path
        paths += path
        hasPrivateHeaders = hasPrivateHeaders || listOf("Cookie", "Authorization", "Referer").any {
            exchange.requestHeaders.containsKey(it)
        }
        if (path != "/denied.js") exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
        val redirect = mapOf("/theme.css" to "/css/theme.css", "/module.js" to "/js/main.js")[path]
        if (redirect != null) {
            exchange.responseHeaders.add("Location", redirect)
            exchange.sendResponseHeaders(302, -1)
        } else {
            val (mime, body) = response(path)
            exchange.responseHeaders.add("Content-Type", mime)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.write(body)
        }
    }

    private fun response(path: String): Pair<String, ByteArray> = when (path) {
        "/css/theme.css" -> "text/css" to """
            @font-face { font-family: HeartbeatProbe; src: url('probe.ttf'); }
            #css { color: rgb(23,45,67); } #font { font-family: HeartbeatProbe; }
        """.trimIndent().encodeToByteArray()

        "/css/probe.ttf" -> "font/ttf" to Base64.getDecoder().decode(PROBE_FONT)

        "/classic.js" ->
            "text/javascript" to
                "document.querySelector('#script').style.width='70px';".encodeToByteArray()

        "/js/main.js" -> "text/javascript" to "import './piece.js';".encodeToByteArray()

        "/js/piece.js" ->
            "text/javascript" to
                "document.querySelector('#module').style.width='73px';".encodeToByteArray()

        "/denied.js" -> "text/javascript" to "document.querySelector('#denied').style.width='99px';".encodeToByteArray()

        else -> "image/svg+xml" to """
            <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18">
            <rect width="18" height="18" fill="gray"/></svg>
        """.trimIndent().encodeToByteArray()
    }

    override fun close() = server.stop(0)
}

private fun BrowserDocumentPreviewResult.Rendered.sample(id: String) = samples.single { "#$id" in it.element }

private fun externalAssetHtml(url: String): String = """
    <!doctype html><link id="sheet" rel="stylesheet" href="$url/theme.css">
    <style>h1,h2 { width:42px;margin:4px;font-size:20px;line-height:24px; }</style>
    <body><h1 id="css">CSS</h1><h2 id="script">Script</h2><h2 id="font">X</h2>
    <h2 id="image">Image</h2><h2 id="module">Module</h2><h2 id="denied">Denied</h2>
    <img src="$url/image.svg" alt="" onload="document.querySelector('#image').style.width='72px'">
    <script src="$url/classic.js"></script><script type="module" src="$url/module.js"></script>
    <script type="module" src="$url/denied.js"></script><script>
    document.fonts.ready.then(()=>document.querySelector('#font').style.width=
      (document.fonts.check('20px HeartbeatProbe','X')?71:99)+'px');
    document.cookie='private=secret';
    fetch('$url/forbidden-fetch').catch(()=>{});
    try { const xhr=new XMLHttpRequest();xhr.open('GET','$url/forbidden-xhr');xhr.send(); } catch {}
    try { navigator.sendBeacon('$url/forbidden-beacon','private'); } catch {}
    try { new Worker('$url/forbidden-worker'); } catch {}
    try { new WebSocket('$url'.replace('http:','ws:')+'/forbidden-socket'); } catch {}
    </script><iframe src="$url/forbidden-frame"></iframe>
    """.trimIndent()

private val ASSET_PATHS = setOf(
    "/theme.css", "/css/theme.css", "/css/probe.ttf", "/classic.js", "/module.js", "/js/main.js", "/js/piece.js",
    "/image.svg", "/denied.js",
)

// Original minimal TrueType test font: one rectangular X glyph, no third-party font or runtime dependency.
private const val PROBE_FONT =
    "AAEAAAAKAIAAAwAgT1MvMmEdUnEAAACsAAAATmNtYXAADACLAAAA/AAAACxnbHlmESI6hAAAASgAAAAYaGVhZGI2RAUAAAFAAAAA" +
        "NmhoZWEGQgPrAAABeAAAACRobXR4B9AAAAAAAZwAAAAIbG9jYQAMAAAAAAGkAAAABm1heHAABAAGAAABrAAAACBuYW1lDSseFQAA" +
        "AcwAAAC8cG9zdAADAAAAAAKIAAAAIAAAAfQBkAAFAAACigJYAAAASwKKAlgAAAFeADIA+gAAAAAAAAAAAAAAAAAAAAEAAAAAAAAA" +
        "AAAAAABIQlRQAEAAAABYAyD/OAAAAyAAyAAAAAAAAQADAAEAAAAMAAQAIAAAAAQABAABAAAAWP//AAAAWP///6kAAQAAAAAAAQAA" +
        "AAADIAMgAAMAADEhESEDIPzgAyAAAQAAAAEAAMX0tVdfDzz1AAMD6AAAAAAAAAAAAAAAAAAAAAAAAAAAAyADIAAAAAgAAgAAAAAA" +
        "AAABAAADIP84AAAD6AAAAMgDIAABAAAAAAAAAAAAAAAAAAAAAgPoAAAD6AAAAAAAAAAMAAAAAQAAAAIABAABAAAAAAACAAAAAAAA" +
        "AAAAAAAAAAAAAAAAAAQANgADAAEECQABAB4AAAADAAEECQACAA4AHgADAAEECQAEAC4ALAADAAEECQAGACwAWgBIAGUAYQByAHQA" +
        "YgBlAGEAdAAgAFAAcgBvAGIAZQBSAGUAZwB1AGwAYQByAEgAZQBhAHIAdABiAGUAYQB0ACAAUAByAG8AYgBlACAAUgBlAGcAdQBs" +
        "AGEAcgBIAGUAYQByAHQAYgBlAGEAdABQAHIAbwBiAGUALQBSAGUAZwB1AGwAYQByAAMAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
        "AAAAAAA="
