package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSecretId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.withoutLabel
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedAnswer
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompt
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPromptConstraints
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPromptFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineContext
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogConnection
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineId
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

@OptIn(ExperimentalCoroutinesApi::class)
class KoogIsolatedPromptsTest {
    @Test
    fun `each aspect has a fresh user-only context without discovery tools history or configuration writes`() =
        runTest {
            val f = fixture()
            val clients = mutableListOf<FakeExecutor>()
            f.isolatedOpen = { request ->
                assertEquals(32, request.maxOutputTokens)
                val executor = FakeExecutor().apply { complete("READY") }
                clients += executor
                KoogClient(
                    executor,
                    reasoning = { error("reasoning discovery forbidden") },
                ) { error("model discovery forbidden") }
            }
            repeat(2) { assertEquals(IsolatedAnswer.Answered("READY"), f.ask()) }
            assertEquals(2, clients.size)
            for (executor in clients) {
                assertEquals(1, executor.closed)
                assertTrue(executor.tools.single().isEmpty())
                val input = executor.prompts.single()
                assertEquals(32, input.params.maxTokens)
                assertEquals(1, input.messages.size)
                assertTrue(input.messages.single() is Message.User)
                assertEquals("READY", input.messages.single().textContent())
            }
            assertEquals(0, f.opens)
            assertEquals(0, f.connections.puts)
            assertEquals(0, f.records.saves)
            assertTrue(f.openedHooks.isEmpty())
        }

    @Test
    fun `unsupported strict transport never falls back to a regular client`() = runTest {
        val f = fixture()
        assertEquals(IsolatedAnswer.Unavailable(IsolatedPromptFailure.IsolationUnproven), f.ask())
        assertEquals(0, f.opens)
        assertTrue(f.executor.prompts.isEmpty())
    }

    @Test
    fun `unsolicited tool frames and truncated or unterminated streams never pass`() = runTest {
        val f = fixture()
        val streams = listOf(
            listOf(StreamFrame.ToolCallDelta("id", "run_command", "{}")),
            listOf(StreamFrame.TextDelta("READY"), StreamFrame.End("length")),
            listOf(StreamFrame.TextDelta("READY")),
        )
        for (frames in streams) {
            val executor = FakeExecutor().apply { streamOverride = flowOf(*frames.toTypedArray()) }
            f.isolatedOpen = { KoogClient(executor) { error("discovery forbidden") } }
            assertEquals(IsolatedAnswer.Unavailable(IsolatedPromptFailure.Incomplete), f.ask())
            assertEquals(1, executor.closed)
            assertEquals(1, executor.prompts.size)
        }
    }

    @Test
    fun `reasoning is not an answer and text completion replaces its deltas within the character limit`() = runTest {
        val f = fixture()
        val executor = FakeExecutor().apply {
            streamOverride = flowOf(
                StreamFrame.ReasoningDelta(text = "private reasoning"),
                StreamFrame.TextDelta("REA"),
                StreamFrame.TextDelta("DY"),
                StreamFrame.TextComplete("READY"),
                StreamFrame.End("stop"),
            )
        }
        f.isolatedOpen = { KoogClient(executor) { error("discovery forbidden") } }
        assertEquals(IsolatedAnswer.Answered("READY"), f.ask(maxChars = 5))
        executor.streamOverride = flowOf(StreamFrame.TextDelta("long answer"), StreamFrame.End("stop"))
        assertEquals(IsolatedAnswer.Unavailable(IsolatedPromptFailure.ResponseTooLarge), f.ask(maxChars = 5))
        assertEquals(2, executor.closed)
    }

    @Test
    fun `same source id and revision with changed scope is rejected before client creation`() = runTest {
        val f = fixture()
        var isOpened = false
        f.isolatedOpen = {
            isOpened = true
            error("not expected")
        }
        f.connections.values[0] = KoogConnection(
            f.binding,
            AuthSource.ManagedKey(
                f.source.info,
                AuthScope(KoogProvider.OpenAI.id, KoogProvider.OpenAI.origin),
                AuthSecretId("replacement"),
            ),
        )
        assertEquals(
            IsolatedAnswer.Unavailable(IsolatedPromptFailure.SourceChanged),
            f.adapter.answerIsolated(
                IsolatedPrompt(f.target, "READY", constraints = IsolatedPromptConstraints(f.source.withoutLabel(), 32)),
                f.source,
                EngineContext(KoogEngineId, f.binding.id, model = f.target.model),
                TimeSource.Monotonic.markNow() + 5.seconds,
                beforeDispatch = {},
            ),
        )
        assertEquals(0, f.secrets.reads)
        assertFalse(isOpened)
    }

    @Test
    fun `source change after client construction is rejected by its before dispatch guard`() = runTest {
        val f = fixture()
        var captured: IsolatedKoogRequest? = null
        f.isolatedOpen = {
            captured = it
            KoogClient(FakeExecutor().apply { complete("READY") }) { error("discovery forbidden") }
        }
        assertEquals(IsolatedAnswer.Answered("READY"), f.ask())
        f.connections.values.clear()
        assertFailsWith<EngineException> { requireNotNull(captured).beforeDispatch() }
    }

    @Test
    fun `request cancellation keeps cleanup owned until its HTTP lease acknowledges closure`() = runTest {
        val f = fixture()
        val executor = FakeExecutor()
        val cleanupStarted = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        f.isolatedOpen = {
            KoogClient(executor, awaitClosed = {
                cleanupStarted.complete(Unit)
                cleanup.await()
            }) { error("discovery forbidden") }
        }
        val call = async { f.ask() }
        runCurrent()
        call.cancel()
        cleanupStarted.await()
        assertFalse(call.isCompleted)
        assertEquals(1, executor.closed)
        cleanup.complete(Unit)
        runCurrent()
        assertTrue(call.isCompleted)
    }

    @Test
    fun `construction cancellation waits for acquired HTTP leases before propagating`() = runTest {
        HttpClient(MockEngine { error("no request expected") }).use { application ->
            val f = fixture()
            val owned = application.config { }
            val pending = holdHttpCleanup(owned)
            val original = CancellationException("construction cancelled")
            f.isolatedOpen = { spec ->
                spec.lease.add(owned)
                throw original
            }
            val call = async { f.ask() }
            try {
                runCurrent()
                assertFalse(call.isCompleted)
            } finally {
                pending.complete(Unit)
            }
            val cancelled = assertFailsWith<CancellationException> { call.await() }
            assertTrue(generateSequence<Throwable>(cancelled) { it.cause }.take(8).any { it === original })
            assertTrue(owned.coroutineContext.job.isCompleted)
            assertTrue(application.coroutineContext.job.isActive)
        }
    }

    @Test
    fun `construction failure retains HTTP cleanup ownership before reporting failure`() = runTest {
        HttpClient(MockEngine { error("no request expected") }).use { application ->
            val f = fixture()
            val owned = application.config { }
            val pending = holdHttpCleanup(owned)
            f.isolatedOpen = { spec ->
                spec.lease.add(owned)
                error("private construction failure")
            }
            val call = async { assertFailsWith<IllegalStateException> { f.ask() } }
            try {
                runCurrent()
                assertFalse(call.isCompleted)
            } finally {
                pending.complete(Unit)
            }
            call.await()
            assertTrue(owned.coroutineContext.job.isCompleted)
            assertTrue(application.coroutineContext.job.isActive)
        }
    }

    @Test
    fun `cleanup joins the intermediate lease and SDK child without closing the application client`() = runTest {
        HttpClient(MockEngine { error("no HTTP request expected") }).use { application ->
            val base = application.config { }
            val pending = holdHttpCleanup(base)
            val lease = OwnedKoogHttpLease().apply { add(base) }
            val factory = OwnedKoogHttpFactory(KtorKoogHttpClient.Factory(baseClient = base), lease)
            val child = factory.create(
                "test",
                "https://llm.example",
                emptyMap(),
                emptyMap(),
                1000,
                1000,
                1000,
                Json,
            )
            val sdkJob = (child as KtorKoogHttpClient).ktorClient.coroutineContext.job
            val cleanup = async { lease.closeAndAwait() }
            try {
                runCurrent()
                assertFalse(cleanup.isCompleted)
            } finally {
                pending.complete(Unit)
            }
            cleanup.await()
            assertTrue(base.coroutineContext.job.isCompleted)
            assertTrue(sdkJob.isCompleted)
            assertTrue(application.coroutineContext.job.isActive)
        }
    }

    private fun kotlinx.coroutines.test.TestScope.fixture(): KoogTestFixture = KoogTestFixture(this).apply {
        connections.values[0] = KoogConnection(
            binding,
            AuthSource.ManagedKey(
                source.info,
                AuthScope(KoogProvider.OpenAI.id, KoogProvider.OpenAI.origin),
                AuthSecretId("key"),
            ),
        )
    }

    private suspend fun KoogTestFixture.ask(
        maxChars: Int = 1024,
        deadline: TimeMark = TimeSource.Monotonic.markNow() +
            5.seconds,
    ): IsolatedAnswer {
        val currentSource = connections.values.firstOrNull()?.source ?: source
        return adapter.answerIsolated(
            IsolatedPrompt(
                target,
                "READY",
                maxAnswerChars = maxChars,
                constraints = IsolatedPromptConstraints(currentSource.withoutLabel(), 32),
            ),
            currentSource,
            EngineContext(KoogEngineId, binding.id, model = target.model),
            deadline,
            beforeDispatch = {},
        )
    }
}
