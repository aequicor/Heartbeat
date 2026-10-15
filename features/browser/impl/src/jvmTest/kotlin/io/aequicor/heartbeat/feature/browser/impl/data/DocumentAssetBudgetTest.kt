package io.aequicor.heartbeat.feature.browser.impl.data

import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserDocumentAssetResult.Content
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserDocumentAssetResult.Redirect
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class DocumentAssetBudgetTest {
    @Test
    fun `rejects announced and actual bodies beyond eight MiB`() = runTest {
        documentAssets {
            respond("", headers = headersOf(HttpHeaders.ContentLength, (RESOURCE_BYTES + 1).toString()))
        }.use { fixture -> assertNull(fixture.assets.open().load(ASSET_URL)) }
        documentAssets { respond(ByteArray(RESOURCE_BYTES + 1)) }.use { fixture ->
            assertNull(fixture.assets.open().load(ASSET_URL))
        }
    }

    @Test
    fun `caps cumulative bytes at thirty two MiB and resets only for a new document`() = runTest {
        val body = ByteArray(RESOURCE_BYTES)
        documentAssets { respond(body) }.use { fixture ->
            val session = fixture.assets.open()
            repeat(4) { assertIs<Content>(session.load(ASSET_URL)) }
            assertNull(session.load(ASSET_URL))
            assertEquals(4, fixture.engine.requestHistory.size)
            assertIs<Content>(fixture.assets.open().load(ASSET_URL))
        }
    }

    @Test
    fun `failed oversized transfers still spend the document byte budget`() = runTest {
        documentAssets { respond(ByteArray(RESOURCE_BYTES + 1)) }.use { fixture ->
            val session = fixture.assets.open()
            repeat(5) { assertNull(session.load(ASSET_URL)) }
            assertEquals(4, fixture.engine.requestHistory.size)
        }
    }

    @Test
    fun `limits requests including failed responses and disables inherited retries`() = runTest {
        documentAssets { respond("error", HttpStatusCode.ServiceUnavailable) }.use { fixture ->
            val session = fixture.assets.open()
            repeat(129) { assertNull(session.load(ASSET_URL)) }
            assertEquals(128, fixture.engine.requestHistory.size)
        }
    }

    @Test
    fun `limits redirect hops across concurrent requests`() = runTest {
        documentAssets {
            respond("", HttpStatusCode.TemporaryRedirect, headersOf(HttpHeaders.Location, "/loop"))
        }.use { fixture ->
            val session = fixture.assets.open()
            val results = List(20) { async { session.load(ASSET_URL) } }.awaitAll()
            assertEquals(16, results.filterIsInstance<Redirect>().size)
            assertEquals(4, results.count { it == null })
        }
    }

    @Test
    fun `allows four simultaneous requests and times out queued work from its original start`() = runTest {
        var active = 0
        var peak = 0
        var started = 0
        documentAssets {
            started++
            active++
            peak = maxOf(peak, active)
            try {
                awaitCancellation()
            } finally {
                active--
            }
        }.use { fixture ->
            val session = fixture.assets.open()
            val requests = List(8) { async { session.load(ASSET_URL) } }
            runCurrent()
            assertEquals(4, started)
            advanceTimeBy(10_000)
            runCurrent()
            assertTrue(requests.awaitAll().all { it == null })
            assertEquals(4, peak)
            assertEquals(4, started)
            assertEquals(0, active)
        }
    }

    @Test
    fun `propagates caller cancellation and releases capacity`() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        var calls = 0
        documentAssets {
            if (++calls == 1) {
                firstStarted.complete(Unit)
                awaitCancellation()
            }
            respond("ok")
        }.use { fixture ->
            val session = fixture.assets.open()
            val first = async { session.load(ASSET_URL) }
            firstStarted.await()
            first.cancelAndJoin()
            assertTrue(first.isCancelled)
            assertIs<Content>(session.load(ASSET_URL))
        }
    }

    @Test
    fun `propagates a shorter caller timeout`() = runTest {
        documentAssets { awaitCancellation() }.use { fixture ->
            assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
                withTimeout(100) { fixture.assets.open().load(ASSET_URL) }
            }
        }
    }

    @Test
    fun `closing profile cancels active loads without closing the shared engine`() = runTest {
        var active = 0
        documentAssets {
            active++
            try {
                awaitCancellation()
            } finally {
                active--
            }
        }.use { fixture ->
            val session = fixture.assets.open()
            val requests = List(8) { async { session.load(ASSET_URL) } }
            runCurrent()
            assertEquals(4, active)
            fixture.profile.close()
            runCurrent()
            requests.forEach { it.join() }
            assertEquals(0, active)
            assertTrue(requests.take(4).all { it.isCancelled })
            assertTrue(requests.drop(4).all { it.await() == null })
            assertNull(fixture.assets.open().load(ASSET_URL))
        }
    }

    private companion object {
        const val RESOURCE_BYTES = 8 * 1024 * 1024
    }
}
