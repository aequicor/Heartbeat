package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class KoogTransportLifecycleTest {
    @Test
    fun `every ordinary SDK transport awaits all owned jobs while preserving the application client`() = runTest {
        for (provider in KoogProvider.entries) {
            val clients = mutableListOf<HttpClient>()
            val tracker = createClientPlugin("LeaseTracker") { clients += client }
            HttpClient(MockEngine { error("no HTTP request expected") }) { install(tracker) }.use { application ->
                val sdk = SdkKoogTransport(application, TestKoogPlatform).open(provider, "synthetic-key", "test")
                val owned = clients.filter { it !== application }
                assertTrue(owned.size >= 2, "Intermediate and SDK children must both be tracked")
                val pending = Job(owned.last().coroutineContext.job)
                val cleanup = async { sdk.closeAndAwait() }
                try {
                    runCurrent()
                    assertFalse(cleanup.isCompleted)
                } finally {
                    pending.complete()
                }
                cleanup.await()
                assertTrue(owned.all { it.coroutineContext.job.isCompleted })
                assertTrue(application.coroutineContext.job.isActive)
            }
        }
    }

    @Test
    fun `ordinary constructor failure awaits the intermediate HTTP job`() = runTest {
        val clients = mutableListOf<HttpClient>()
        val tracker = createClientPlugin("LeaseTracker") { clients += client }
        HttpClient(MockEngine { error("no HTTP request expected") }) { install(tracker) }.use { application ->
            assertFailsWith<IllegalArgumentException> {
                SdkKoogTransport(application, TestKoogPlatform).open(KoogProvider.OpenAI, null)
            }
            val owned = clients.filter { it !== application }
            assertEquals(1, owned.size)
            assertTrue(owned.all { it.coroutineContext.job.isCompleted })
            assertTrue(application.coroutineContext.job.isActive)
        }
    }

    @Test
    fun `cancellation during acquisition closes SDK children before cancellation escapes`() = runTest {
        val clients = mutableListOf<HttpClient>()
        var acquisition: Job? = null
        val original = CancellationException("synthetic acquisition cancellation")
        val tracker = createClientPlugin("LeaseTracker") {
            clients += client
            acquisition?.cancel(original)
        }
        HttpClient(MockEngine { error("no HTTP request expected") }) { install(tracker) }.use { application ->
            val call = async {
                acquisition = currentCoroutineContext().job
                SdkKoogTransport(application, TestKoogPlatform).open(KoogProvider.OpenAI, "synthetic-key")
            }
            assertFailsWith<CancellationException> { call.await() }
            val owned = clients.filter { it !== application }
            assertTrue(owned.isNotEmpty())
            assertTrue(owned.all { it.coroutineContext.job.isCompleted })
            assertTrue(application.coroutineContext.job.isActive)
        }
    }

    @Test
    fun `cancellation inside an ordinary operation awaits HTTP teardown`() = runTest {
        val clients = mutableListOf<HttpClient>()
        val tracker = createClientPlugin("LeaseTracker") { clients += client }
        HttpClient(MockEngine { error("no HTTP request expected") }) { install(tracker) }.use { application ->
            val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val call = async {
                SdkKoogTransport(application, TestKoogPlatform).open(KoogProvider.OpenAI, "synthetic-key")
                    .useAndAwait {
                        entered.complete(Unit)
                        kotlinx.coroutines.awaitCancellation()
                    }
            }
            entered.await()
            val owned = clients.filter { it !== application }
            val pending = Job(owned.last().coroutineContext.job)
            call.cancel()
            try {
                runCurrent()
                assertFalse(call.isCompleted)
            } finally {
                pending.complete()
            }
            assertFailsWith<CancellationException> { call.await() }
            assertTrue(owned.all { it.coroutineContext.job.isCompleted })
            assertTrue(application.coroutineContext.job.isActive)
        }
    }

    @Test
    fun `closing a neighbouring SDK client preserves requests through the shared application transport`() = runTest {
        var requests = 0
        val engine = MockEngine {
            requests++
            respond(
                """{"object":"list","data":[{"id":"test","object":"model","created":0,"owned_by":"test"}]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        HttpClient(engine).use { application ->
            val transport = SdkKoogTransport(application, TestKoogPlatform)
            transport.open(KoogProvider.OpenAI, "synthetic-key").useAndAwait { neighbour ->
                transport.open(KoogProvider.OpenAI, "synthetic-key").closeAndAwait()
                assertEquals("test", neighbour.models().single().id)
            }
            assertEquals(1, requests)
            assertTrue(application.coroutineContext.job.isActive)
        }
    }

    @Test
    fun `a throwing SDK close plugin cannot strand jobs or close the application transport`() = runTest {
        val failures = listOf(IllegalStateException("synthetic failure"), CancellationException("synthetic cancel"))
        for (original in failures) {
            val clients = mutableListOf<HttpClient>()
            val tracker = createClientPlugin("ThrowingLeaseTracker") {
                val isSdkChild = clients.size == 2
                clients += client
                onClose { if (isSdkChild) throw original }
            }
            HttpClient(MockEngine { respond("ok") }) { install(tracker) }.use { application ->
                val sdk = SdkKoogTransport(application, TestKoogPlatform).open(KoogProvider.OpenAI, "synthetic-key")
                val failure = assertFailsWith<Exception> { sdk.closeAndAwait() }
                assertSame(original, failure)
                assertTrue(clients.filter { it !== application }.all { it.coroutineContext.job.isCompleted })
                assertTrue(application.coroutineContext.job.isActive)
                assertEquals(200, application.get("https://offline.example").status.value)
            }
        }
    }

    @Test
    fun `a broken close plugin does not prevent closure of remaining owned clients`() = runTest {
        HttpClient(MockEngine { error("no request expected") }).use { application ->
            val original = IllegalStateException("synthetic close failure")
            val broken = application.config {
                install(createClientPlugin("BrokenClose") { onClose { throw original } })
            }
            val sibling = application.config { }
            val lease = OwnedKoogHttpLease().apply {
                add(broken)
                add(sibling)
            }
            assertSame(original, assertFailsWith<IllegalStateException> { lease.closeAndAwait() })
            assertTrue(broken.coroutineContext.job.isCompleted)
            assertTrue(sibling.coroutineContext.job.isCompleted)
            assertTrue(application.coroutineContext.job.isActive)
        }
    }

    @Test
    fun `executor cancellation survives errors from owned close and awaited cleanup`() = runTest {
        HttpClient(MockEngine { error("no request expected") }).use { application ->
            val closeError = IllegalStateException("synthetic owned close failure")
            val awaitError = IllegalStateException("synthetic await failure")
            val original = CancellationException("synthetic executor cancellation")
            val owned = application.config {
                install(createClientPlugin("BrokenClose") { onClose { throw closeError } })
            }
            val lease = OwnedKoogHttpLease().apply { add(owned) }
            val executor = FakeExecutor().apply { closeFailure = original }
            val sdk = KoogClient(
                executor,
                closeOwned = lease::close,
                awaitClosed = {
                    lease.closeAndAwait()
                    throw awaitError
                },
            ) { error("discovery forbidden") }
            val failure = assertFailsWith<CancellationException> { sdk.closeAndAwait() }
            assertSame(original, failure)
            assertTrue(failure.suppressedExceptions.contains(closeError))
            assertTrue(
                failure.suppressedExceptions.any { error ->
                    generateSequence(error) { it.cause }.take(8).any { it === awaitError }
                },
            )
            assertTrue(owned.coroutineContext.job.isCompleted)
            assertTrue(application.coroutineContext.job.isActive)
        }
    }

    @Test
    fun `operation cancellation survives a failed transport close`() = runTest {
        val original = CancellationException("synthetic operation cancellation")
        val closeError = IllegalStateException("synthetic close failure")
        val executor = FakeExecutor().apply { closeFailure = closeError }
        val sdk = KoogClient(executor) { error("discovery forbidden") }
        assertSame(
            original,
            assertFailsWith<CancellationException> {
                sdk.useAndAwait { throw original }
            },
        )
        assertTrue(
            original.suppressedExceptions.any { error ->
                generateSequence(error) { it.cause }.take(8).any { it === closeError }
            },
        )
        assertEquals(1, executor.closed)
    }

    @Test
    fun `executor close failure still closes owned HTTP clients and awaits jobs`() = runTest {
        val application = HttpClient(MockEngine { error("no request expected") })
        try {
            val owned = application.config { }
            val lease = OwnedKoogHttpLease().apply { add(owned) }
            val executor = FakeExecutor().apply { closeFailure = IllegalStateException("synthetic close failure") }
            val client = KoogClient(
                executor,
                closeOwned = lease::close,
                awaitClosed = lease::closeAndAwait,
            ) { error("discovery forbidden") }
            assertFailsWith<IllegalStateException> { client.closeAndAwait() }
            assertEquals(1, executor.closed)
            assertTrue(owned.coroutineContext.job.isCompleted)
            assertTrue(application.coroutineContext.job.isActive)
        } finally {
            application.close()
        }
    }
}
