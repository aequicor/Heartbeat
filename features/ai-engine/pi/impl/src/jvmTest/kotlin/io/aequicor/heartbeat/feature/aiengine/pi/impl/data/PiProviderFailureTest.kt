package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.LogSink
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RetryAdvice
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.retryAdvice
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PiProviderFailureTest {
    @Test
    fun `provider failure remains provisional and a successful retry replaces it`() = runTest {
        val fixture = fixture(this)
        fixture.runningTurn()
        fixture.connection.event(
            record(
                """{"type":"message_end","message":{"role":"assistant","content":[],
                "stopReason":"error","errorMessage":"503: unavailable"}}""",
            ),
        )
        assertIs<ActiveSessionState.Running>(fixture.session.state.value)
        fixture.connection.event(
            record("""{"type":"message_end","message":{"role":"assistant","content":[],"stopReason":"stop"}}"""),
        )
        fixture.connection.event(record("""{"type":"agent_settled"}"""))
        assertEquals(
            TurnOutcome.Completed,
            assertIs<ActiveSessionState.Ready>(fixture.session.state.value).lastTurn?.outcome,
        )
        fixture.session.shutdown()
    }

    @Test
    fun `settled provider error exposes its classified failure`() = runTest {
        val fixture = fixture(this)
        fixture.runningTurn()
        fixture.connection.event(
            record(
                """{"type":"message_end","message":{"role":"assistant","content":[],
                "stopReason":"error","errorMessage":"429: rate limited"}}""",
            ),
        )
        fixture.connection.event(record("""{"type":"agent_settled"}"""))
        val ready = assertIs<ActiveSessionState.Ready>(fixture.session.state.value)
        assertIs<EngineFailure.RateLimited>(assertIs<TurnOutcome.Failed>(ready.lastTurn?.outcome).failure)
        fixture.session.shutdown()
    }

    @Test
    fun `native Pi HTTP errors retain established causes`() {
        // Captured from bundled Pi 0.87.1 against a local OpenAI-compatible fixture server.
        val auth = piProviderFailure("""401: {"code":"test_failure","message":"Local fixture rejection"}""")
        assertEquals(AuthFailureReason.CredentialsRejected, assertIs<EngineFailure.Authentication>(auth).reason.reason)
        assertEquals(
            AccessFailureReason.ModelAccessDenied,
            assertIs<EngineFailure.Access>(piProviderFailure("403 status code (no body)")).reason,
        )
        assertIs<EngineFailure.RateLimited>(piProviderFailure("429: {\"code\":\"test_failure\"}"))
        listOf(400, 404, 413, 422).forEach { status ->
            assertEquals(
                RequestFailureReason.Invalid,
                assertIs<EngineFailure.Request>(piProviderFailure("$status: rejected")).reason,
            )
        }
        listOf(500, 502, 503, 599).forEach { status ->
            assertEquals(
                TransportFailureReason.ServiceUnavailable,
                assertIs<EngineFailure.Transport>(piProviderFailure("$status server error")).reason,
            )
        }
    }

    @Test
    fun `exhausted quota requires user action while a rate limit can wait`() {
        listOf(
            """429: {"code":"insufficient_quota","message":"Check your plan and billing details."}""",
            """429 {"error":{"code":"insufficient_quota","message":"Quota exceeded"}}""",
        ).forEach { message ->
            val failure = assertIs<EngineFailure.QuotaExceeded>(piProviderFailure(message))
            assertEquals(RetryAdvice.AfterUserAction, failure.retryAdvice())
        }
        val rate = piProviderFailure("""429: {"code":"rate_limit_exceeded"}""")
        assertIs<EngineFailure.RateLimited>(rate)
        assertEquals(RetryAdvice.AfterDelay(null), rate.retryAdvice())
    }

    @Test
    fun `only a structured context code establishes an exhausted context`() {
        listOf(400, 413, 422).forEach { status ->
            assertIs<EngineFailure.ContextLimitExceeded>(
                piProviderFailure("""$status: {"error":{"code":"context_length_exceeded"}}"""),
            )
        }
        assertEquals(
            RequestFailureReason.Invalid,
            assertIs<EngineFailure.Request>(piProviderFailure("413 Request Entity Too Large")).reason,
        )
        assertIs<EngineFailure.Request>(piProviderFailure("""413: {"code":"image_too_large"}"""))
    }

    @Test
    fun `quoted nested malformed and mismatched limit codes keep the HTTP fallback`() {
        listOf(
            """429: {"message":"insufficient_quota"}""",
            """429: {"metadata":{"code":"insufficient_quota"}}""",
            """429: {"message":"{\"code\":\"insufficient_quota\"}"}""",
            """429: {"code":"insufficient_quota""",
            """429: {"code":"context_length_exceeded"}""",
        ).forEach { assertIs<EngineFailure.RateLimited>(piProviderFailure(it)) }
        assertIs<EngineFailure.Request>(piProviderFailure("""400: {"code":"insufficient_quota"}"""))
        assertIs<EngineFailure.Authentication>(piProviderFailure("""401: {"code":"context_length_exceeded"}"""))
        assertIs<EngineFailure.Request>(piProviderFailure("""413: {"message":"context_length_exceeded"}"""))
    }

    @Test
    fun `only exact transport diagnostics and status prefixes are recognized`() {
        listOf("408 timeout", "504: timeout", "Request timed out.").forEach { message ->
            assertEquals(
                TransportFailureReason.Timeout,
                assertIs<EngineFailure.Transport>(piProviderFailure(message)).reason,
            )
        }
        listOf("Connection error.", "fetch failed").forEach { message ->
            assertEquals(
                TransportFailureReason.NetworkUnavailable,
                assertIs<EngineFailure.Transport>(piProviderFailure(message)).reason,
            )
        }
        assertEquals(
            TransportFailureReason.ProtocolViolation,
            assertIs<EngineFailure.Transport>(piProviderFailure("Stream ended without finish_reason")).reason,
        )
        listOf(null, "", "4010", "401secret", "Input contains 401", "echo: Connection error.").forEach { message ->
            assertIs<EngineFailure.Unknown>(piProviderFailure(message))
        }
    }

    @Test
    fun `release diagnostics include the status but never provider text or causes`() {
        val records = mutableListOf<Pair<Throwable?, String>>()
        Log.init(isDebug = false, sinks = listOf(LogSink { _, _, error, message -> records += error to message }))
        try {
            piProviderFailure("401: private-key private-prompt private-header")
            piProviderFailure("private-key private-prompt private-header")
            assertEquals(2, records.size)
            assertTrue(records.first().second.contains("status=401"))
            assertTrue(records.last().second.contains("code=unknown status=none"))
            piProviderFailure("""429: {"code":"insufficient_quota","message":"private-key private-prompt"}""")
            piProviderFailure("""400: {"message":"private-key private-prompt"""")
            assertEquals(5, records.size)
            records.forEach { (error, message) ->
                assertFalse("private-" in message)
                assertFalse("private-" in error?.stackTraceToString().orEmpty())
                assertNull(error?.cause)
            }
        } finally {
            Log.init(isDebug = false)
        }
    }
}
