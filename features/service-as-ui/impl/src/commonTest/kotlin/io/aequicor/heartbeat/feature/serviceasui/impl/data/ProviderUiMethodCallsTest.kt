package io.aequicor.heartbeat.feature.serviceasui.impl.data

import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiLimits
import io.aequicor.heartbeat.feature.serviceasui.api.UiCallContext
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodCall
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodFailure
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodResult
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodReview
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethods
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwner
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwners
import io.aequicor.heartbeat.feature.serviceasui.api.spi.ServiceUiProvider
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiMethodCalls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ProviderUiMethodCallsTest {
    private val owner = UiOwner("harness", "h_1")
    private val empty = JsonObject(emptyMap())

    @Test
    fun `calls reach the provider of the owner's kind`() = runTest {
        val provider = FakeProvider { UiMethodResult.Success(JsonPrimitive("${it.method} ${it.arguments["repo"]}")) }
        val calls = ProviderUiMethodCalls(setOf(provider))
        val arguments = JsonObject(mapOf("repo" to JsonPrimitive("a/b")))

        assertEquals(
            UiMethodResult.Success(JsonPrimitive("prs \"a/b\"")),
            calls.call(owner, "prs", arguments, UiCallContext()),
        )
        assertEquals(UiMethods(true), calls.methods(owner).first())
        val stranger = UiOwner("plugin", "p")
        assertEquals(
            UiMethodResult.Failure(UiMethodFailure.Unavailable),
            calls.call(stranger, "prs", empty, UiCallContext()),
        )
        assertEquals(UiMethods(isAvailable = false), calls.methods(stranger).first())
    }

    @Test
    fun `oversized arguments and results are refused`() = runTest {
        val provider = FakeProvider { UiMethodResult.Success(JsonPrimitive("x".repeat(RESULT_BYTES))) }
        val calls = ProviderUiMethodCalls(setOf(provider))
        val big = JsonObject(mapOf("text" to JsonPrimitive("x".repeat(ServiceUiLimits.ARGS_BYTES))))

        assertEquals(UiMethodResult.Failure(UiMethodFailure.TooLarge), calls.call(owner, "prs", big, UiCallContext()))
        assertEquals(0, provider.calls)
        assertEquals(UiMethodResult.Failure(UiMethodFailure.TooLarge), calls.call(owner, "prs", empty, UiCallContext()))
    }

    @Test
    fun `failing hanging and broken providers end as results`() = runTest {
        val failing = ProviderUiMethodCalls(setOf(FakeProvider { error("provider bug") }))
        assertEquals(UiMethodResult.Failure(UiMethodFailure.Failed), failing.call(owner, "prs", empty, UiCallContext()))

        val hanging = ProviderUiMethodCalls(setOf(FakeProvider { awaitCancellation() }))
        assertEquals(
            UiMethodResult.Failure(UiMethodFailure.TimedOut),
            hanging.call(owner, "prs", empty, UiCallContext()),
        )

        val broken = ProviderUiMethodCalls(
            setOf(FakeProvider(methods = flow { error("stream bug") }) { error("unused") }),
        )
        assertEquals(UiMethods(isAvailable = false), broken.methods(owner).first())
    }

    @Test
    fun `active owners of every provider combine and a broken provider counts as none`() = runTest {
        val calls = ProviderUiMethodCalls(
            setOf(
                FakeProvider(active = flowOf(setOf("h_1", "bad:id"))) { error("unused") },
                FakeProvider(kind = "plugin", active = flowOf(setOf("p_1"))) { error("unused") },
                FakeProvider(kind = "broken", active = flow { error("stream bug") }) { error("unused") },
            ),
        )

        assertEquals(
            setOf(owner, UiOwner("plugin", "p_1")),
            calls.activeOwners(UiCallContext()).first(),
        )
        assertEquals(emptySet(), ProviderUiMethodCalls(emptySet()).activeOwners(UiCallContext()).first())
    }

    @Test
    fun `revision failures still allow a surface to perform its initial load`() = runTest {
        val provider = FakeProvider(revision = flow { error("stream bug") }) { error("unused") }
        val calls = ProviderUiMethodCalls(setOf(provider))
        assertEquals(0L, calls.dataRevision(owner).first())
        assertEquals(0L, calls.dataRevision(UiOwner("missing", "owner")).first())
        assertEquals(0, provider.calls)
    }

    @Test
    fun `review snapshots do not execute methods and reviewed calls retain their binding`() = runTest {
        val review = UiMethodReview("activation-registration", "private source")
        val provider = FakeProvider(review = { review }) { call ->
            assertEquals(review.binding, call.expectedBinding)
            UiMethodResult.Success(JsonPrimitive("ok"))
        }
        val calls = ProviderUiMethodCalls(setOf(provider))
        assertEquals(review, calls.review(owner, "prs", UiCallContext()))
        assertEquals(0, provider.calls)
        assertEquals(
            UiMethodResult.Success(JsonPrimitive("ok")),
            calls.call(owner, UiMethodCall("prs", empty, UiCallContext(), review.binding)),
        )
        assertEquals(1, provider.calls)
    }

    @Test
    fun `unavailable failed and timed out reviews deny execution while cancellation propagates`() = runTest {
        assertNull(ProviderUiMethodCalls(emptySet()).review(owner, "prs", UiCallContext()))
        for (reviewer in listOf<suspend () -> UiMethodReview?>(
            { null },
            { error("private provider message") },
            { awaitCancellation() },
        )) {
            val provider = FakeProvider(review = reviewer) { error("must not call") }
            assertNull(ProviderUiMethodCalls(setOf(provider)).review(owner, "prs", UiCallContext()))
            assertEquals(0, provider.calls)
        }
        val cancelled = FakeProvider(review = { throw CancellationException() }) { error("must not call") }
        assertFailsWith<CancellationException> {
            ProviderUiMethodCalls(setOf(cancelled)).review(owner, "prs", UiCallContext())
        }
    }

    @Test
    fun `legacy method port never silently drops a reviewed binding`() = runTest {
        val calls = object : UiMethodCalls {
            override fun activeOwners(context: UiCallContext): Flow<Set<UiOwner>> = flowOf(emptySet())
            override fun methods(owner: UiOwner): Flow<UiMethods> = flowOf(UiMethods(false))
            override suspend fun call(
                owner: UiOwner,
                method: String,
                arguments: JsonObject,
                context: UiCallContext,
            ): UiMethodResult = error("Bound call must not downgrade to an unbound action")
        }
        assertNull(calls.review(owner, "prs", UiCallContext()))
        assertEquals(
            UiMethodResult.Failure(UiMethodFailure.Unavailable),
            calls.call(owner, UiMethodCall("prs", empty, UiCallContext(), "reviewed")),
        )
    }

    private class FakeProvider(
        override val kind: String = "harness",
        private val methods: Flow<UiMethods> = flowOf(UiMethods(true)),
        private val active: Flow<Set<String>> = flowOf(emptySet()),
        private val revision: Flow<Long> = flowOf(0L),
        private val review: suspend () -> UiMethodReview? = { null },
        private val handler: suspend (UiMethodCall) -> UiMethodResult,
    ) : ServiceUiProvider {
        var calls = 0
        override val owners: Flow<UiOwners> = flowOf(UiOwners(isComplete = true))
        override fun activeOwners(context: UiCallContext): Flow<Set<String>> = active
        override fun methods(owner: String): Flow<UiMethods> = methods
        override fun dataRevision(owner: String): Flow<Long> = revision
        override suspend fun review(owner: String, method: String, context: UiCallContext): UiMethodReview? = review()

        override suspend fun call(owner: String, call: UiMethodCall): UiMethodResult {
            calls++
            return handler(call)
        }
    }
}
