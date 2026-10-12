package io.aequicor.heartbeat.feature.serviceasui.impl.data

import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiLimits
import io.aequicor.heartbeat.feature.serviceasui.api.UiCallContext
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodCall
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodFailure
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodResult
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethods
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwner
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwners
import io.aequicor.heartbeat.feature.serviceasui.api.spi.ServiceUiProvider
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
    fun `failing, hanging and broken providers end as results`() = runTest {
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

    private class FakeProvider(
        private val methods: Flow<UiMethods> = flowOf(UiMethods(true)),
        private val handler: suspend (UiMethodCall) -> UiMethodResult,
    ) : ServiceUiProvider {
        var calls = 0
        override val kind = "harness"
        override val owners: Flow<UiOwners> = flowOf(UiOwners(isComplete = true))
        override fun activeOwners(context: UiCallContext): Flow<Set<String>> = flowOf(emptySet())
        override fun methods(owner: String): Flow<UiMethods> = methods

        override suspend fun call(owner: String, call: UiMethodCall): UiMethodResult {
            calls++
            return handler(call)
        }
    }
}
