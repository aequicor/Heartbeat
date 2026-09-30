@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReportsProviderUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionContextUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class CodexUsageTest {
    @Test
    fun `context uses last request not lifetime and invalid telemetry clears it`() {
        val context = CodexContextUsage()
        context.receive(
            usageObject(
                """
                {"tokenUsage": {"total": {"totalTokens": 900000}, "last": {"totalTokens": 61},
                "modelContextWindow": 100}}
                """,
            ),
        )
        assertEquals(61L, context.state.value?.usedTokens)
        assertFalse(context.state.value!!.observation.isStale)
        context.receive(
            usageObject(
                """
                {"tokenUsage": {"last": {"totalTokens": 12}, "modelContextWindow": null}}
                """,
            ),
        )
        assertNull(context.state.value)
        context.receive(
            usageObject(
                """
                {"tokenUsage": {"last": {"totalTokens": -1}, "modelContextWindow": 100}}
                """,
            ),
        )
        assertNull(context.state.value)
    }

    @Test
    fun `multi bucket snapshot overrides legacy and sparse updates retain reset and credits`() = runTest {
        val provider = CodexProviderUsage(StandardTestDispatcher(testScheduler)) {
            usageObject(
                """
                {"rateLimits": {"primary": {"usedPercent": 99}}, "rateLimitsByLimitId": {"codex":
                {"limitId": "codex", "planType": "pro", "primary": {"usedPercent": 25,
                "windowDurationMins": 300, "resetsAt": 100}, "credits": {"balance": "12.50",
                "unlimited": false}}, "model": {"limitId": "model", "secondary": {"usedPercent": 0,
                "windowDurationMins": 10080}}}}
                """,
            )
        }
        assertEquals(2, provider.refresh().windows.size)
        provider.receive(
            usageObject(
                """
                {"rateLimits": {"limitId": "codex", "planType": null, "credits": null, "primary":
                {"usedPercent": 50, "resetsAt": null}}}
                """,
            ),
        )
        val snapshot = provider.state.value
        assertEquals("pro", snapshot.planName)
        assertEquals(12.5, snapshot.credits?.remaining)
        assertEquals(50.0, snapshot.windows.first().usedPercent)
        assertEquals(Instant.fromEpochSeconds(100), snapshot.windows.first().resetsAt)
        assertEquals(300L, snapshot.windows.first().windowDurationMinutes)
    }

    @Test
    fun `runtime routes context and invalidates it during compaction`() = runTest {
        val fixture = Fixture(this)
        val session = fixture.open()
        val context = assertIs<FeatureAccess.Available<SessionContextUsage>>(
            session.features.resolve(SessionContextUsage),
        ).feature
        fixture.event(
            "thread/tokenUsage/updated",
            "tokenUsage" to usageObject(
                """
                {"last": {"totalTokens": 60}, "modelContextWindow": 100}
                """,
            ),
        )
        runCurrent()
        assertEquals(60L, context.state.value?.usedTokens)
        fixture.event("thread/compacted")
        runCurrent()
        assertNull(context.state.value)
        fixture.runtime.close()
    }

    @Test
    fun `quota refresh validates native account before reading and clears retired data`() = runTest {
        val fixture = Fixture(this)
        fixture.open()
        val handle = fixture.wire.handler
        fixture.wire.handler = { request ->
            if (request.text("method") == "account/rateLimits/read") {
                fixture.wire.reply(
                    request,
                    usageObject(
                        """{"rateLimits":{"primary":{"usedPercent":20,"windowDurationMins":300}}}""",
                    ),
                )
            } else {
                handle(request)
            }
        }
        val provider = assertIs<FeatureAccess.Available<ReportsProviderUsage>>(
            fixture.runtime.features.resolve(ReportsProviderUsage),
        ).feature
        fixture.event(
            "account/rateLimits/updated",
            "rateLimits" to usageObject("""{"primary":{"usedPercent":99}}"""),
        )
        runCurrent()
        assertEquals(emptyList(), provider.state.value.windows)
        assertEquals(20.0, provider.refresh().windows.single().usedPercent)
        fixture.event("account/updated")
        fixture.event(
            "account/rateLimits/updated",
            "rateLimits" to usageObject("""{"primary":{"usedPercent":99}}"""),
        )
        runCurrent()
        assertEquals(emptyList(), provider.state.value.windows)
        fixture.account = usageObject("""{"type":"chatgpt","email":"other@example.invalid"}""")
        assertFailsWith<EngineException> { provider.refresh() }
        assertEquals(emptyList(), provider.state.value.windows)
        assertEquals(1, fixture.wire.written.count { it.text("method") == "account/rateLimits/read" })
    }

    @Test
    fun `transient quota refresh preserves native snapshot and later sparse updates`() = runTest {
        val fixture = Fixture(this)
        fixture.open()
        val handle = fixture.wire.handler
        var failure: EngineException? = null
        fixture.wire.handler = { request ->
            if (request.text("method") == "account/rateLimits/read") {
                failure?.let { throw it }
                fixture.wire.reply(
                    request,
                    usageObject(
                        """{"rateLimits":{"planType":"pro","primary":{"usedPercent":20,
                        "windowDurationMins":300,"resetsAt":100},"credits":{"balance":"12.50"}}}""",
                    ),
                )
            } else {
                handle(request)
            }
        }
        val provider = assertIs<FeatureAccess.Available<ReportsProviderUsage>>(
            fixture.runtime.features.resolve(ReportsProviderUsage),
        ).feature
        val previous = provider.refresh()
        failure = EngineException(EngineFailure.Transport(TransportFailureReason.NetworkUnavailable))
        assertEquals(failure, assertFailsWith<EngineException> { provider.refresh() })
        assertEquals(previous.windows, provider.state.value.windows)
        assertEquals(previous.credits, provider.state.value.credits)
        assertEquals(previous.observation.checkedAt, provider.state.value.observation.checkedAt)
        assertTrue(provider.state.value.observation.isStale)
        fixture.event(
            "account/rateLimits/updated",
            "rateLimits" to usageObject("""{"primary":{"usedPercent":30}}"""),
        )
        runCurrent()
        val current = provider.state.value
        assertEquals(previous.windows.single().copy(usedPercent = 30.0), current.windows.single())
        assertEquals(previous.credits, current.credits)
        assertEquals(previous.planName, current.planName)
        assertFalse(current.observation.isStale)
        fixture.runtime.close()
    }

    @Test
    fun `invalid native account clears quotas and rejects following sparse update`() = runTest {
        val fixture = Fixture(this)
        fixture.open()
        val handle = fixture.wire.handler
        fixture.wire.handler = { request ->
            if (request.text("method") == "account/rateLimits/read") {
                fixture.wire.reply(request, usageObject("""{"rateLimits":{"primary":{"usedPercent":20}}}"""))
            } else {
                handle(request)
            }
        }
        val provider = assertIs<FeatureAccess.Available<ReportsProviderUsage>>(
            fixture.runtime.features.resolve(ReportsProviderUsage),
        ).feature
        assertEquals(20.0, provider.refresh().windows.single().usedPercent)
        fixture.account = usageObject("""{"type":"apiKey"}""")
        assertIs<EngineFailure.Authentication>(assertFailsWith<EngineException> { provider.refresh() }.failure)
        fixture.event(
            "account/rateLimits/updated",
            "rateLimits" to usageObject("""{"primary":{"usedPercent":99}}"""),
        )
        runCurrent()
        assertEquals(emptyList(), provider.state.value.windows)
        fixture.runtime.close()
    }

    @Test
    fun `account event during pending quota refresh rejects response without account id`() = runTest {
        val fixture = Fixture(this)
        fixture.open()
        val handle = fixture.wire.handler
        val requested = CompletableDeferred<JsonObject>()
        fixture.wire.handler = { request ->
            if (request.text("method") == "account/rateLimits/read") {
                requested.complete(request)
            } else {
                handle(request)
            }
        }
        val provider = assertIs<FeatureAccess.Available<ReportsProviderUsage>>(
            fixture.runtime.features.resolve(ReportsProviderUsage),
        ).feature
        val refresh = async { provider.refresh() }
        val request = requested.await()
        fixture.event("account/updated")
        runCurrent()
        fixture.wire.reply(request, usageObject("""{"rateLimits":{"primary":{"usedPercent":20}}}"""))
        assertEquals(emptyList(), refresh.await().windows)
        fixture.event(
            "account/rateLimits/updated",
            "rateLimits" to usageObject("""{"primary":{"usedPercent":99}}"""),
        )
        runCurrent()
        assertEquals(emptyList(), provider.state.value.windows)
        fixture.runtime.close()
    }

    @Test
    fun `disabled telemetry makes no quota request and ignores context events`() = runTest {
        val fixture = Fixture(this)
        fixture.isUsageEnabled = false
        val session = fixture.open()
        val provider = assertIs<FeatureAccess.Available<ReportsProviderUsage>>(
            fixture.runtime.features.resolve(ReportsProviderUsage),
        ).feature
        assertEquals(emptyList(), provider.refresh().windows)
        assertFalse(fixture.wire.written.any { it.text("method") == "account/rateLimits/read" })
        fixture.event(
            "thread/tokenUsage/updated",
            "tokenUsage" to usageObject(
                """
                {"last": {"totalTokens": 60}, "modelContextWindow": 100}
                """,
            ),
        )
        runCurrent()
        val context = assertIs<FeatureAccess.Available<SessionContextUsage>>(
            session.features.resolve(SessionContextUsage),
        ).feature
        assertNull(context.state.value)
        fixture.runtime.close()
    }
}

private fun usageObject(value: String): JsonObject = Json.parseToJsonElement(value) as JsonObject
