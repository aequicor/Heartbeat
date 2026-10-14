package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.LogSink
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.RetryAdvice
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
class PiZaiProviderFailureTest {
    @Test
    fun `coding plan quota and expiration codes require user action`() {
        listOf("1113", "1308", "1309", "1310", "1314", "1316", "1317", "1318", "1319", "1320", "1321")
            .forEach { code ->
                listOf(
                    """429: {"code":"$code","message":"Plan unavailable"}""",
                    """429 {"error":{"code":$code,"message":"Plan unavailable"}}""",
                ).forEach { message ->
                    val failure = assertIs<EngineFailure.QuotaExceeded>(piProviderFailure(message, "zai"))
                    assertEquals(RetryAdvice.AfterUserAction, failure.retryAdvice())
                }
            }
    }

    @Test
    fun `coding plan model access and product key failures retain their cause`() {
        listOf(
            """429: {"code":"1311"}""",
            """429: {"error":{"code":1311}}""",
        ).forEach { message ->
            val failure = assertIs<EngineFailure.Access>(piProviderFailure(message, "zai"))
            assertEquals(AccessFailureReason.ModelAccessDenied, failure.reason)
            assertEquals(RetryAdvice.AfterUserAction, failure.retryAdvice())
        }
        listOf(
            """429: {"code":1315}""",
            """429: {"error":{"code":"1315"}}""",
        ).forEach { message ->
            val failure = assertIs<EngineFailure.Authentication>(piProviderFailure(message, "zai"))
            assertEquals(AuthFailureReason.AuthMismatch, failure.reason.reason)
            assertEquals(RetryAdvice.AfterUserAction, failure.retryAdvice())
        }
    }

    @Test
    fun `coding plan policy restriction requires restoring access with the provider`() {
        listOf(
            """429: {"code":"1313"}""",
            """429: {"error":{"code":1313}}""",
        ).forEach { message ->
            val failure = assertIs<EngineFailure.Access>(piProviderFailure(message, "zai"))
            assertEquals(AccessFailureReason.OperationNotAllowed, failure.reason)
            assertEquals(RetryAdvice.AfterUserAction, failure.retryAdvice())
        }
    }

    @Test
    fun `only native zai interprets documented exact business codes`() {
        listOf(null, "openai", "openai-compatible", "zai-coding-cn", "ZAI").forEach { provider ->
            listOf("1113", "1311", "1313", "1315").forEach { code ->
                assertIs<EngineFailure.RateLimited>(piProviderFailure("""429: {"code":"$code"}""", provider))
            }
        }
        listOf("1302", "1305", "9999", "11130", " 1113", "1113.0", "1.113e3").forEach { code ->
            val failure = piProviderFailure("""429: {"code":"$code"}""", "zai")
            assertIs<EngineFailure.RateLimited>(failure)
            assertEquals(RetryAdvice.AfterDelay(null), failure.retryAdvice())
        }
        assertIs<EngineFailure.Request>(piProviderFailure("""400: {"code":"1113"}""", "zai"))
        assertIs<EngineFailure.Unknown>(piProviderFailure("""{"code":"1113"}""", "zai"))
    }

    @Test
    fun `malformed nested and oversized bodies never establish coding plan errors`() {
        listOf(
            """429: {}""",
            """429: {"message":"1113"}""",
            """429: {"metadata":{"code":"1113"}}""",
            """429: {"message":"{\"code\":\"1113\"}"}""",
            """429: {"code":"1113""",
            """429: {"code":1113.0}""",
            """429: {"code":1.113e3}""",
            """429: {"code":true}""",
            """429: {"code":null}""",
            """429: {"code":["1113"]}""",
            """429: {"code":{"value":"1113"}}""",
            """429: {"code":"1113","message":"${"x".repeat(16_384)}"}""",
        ).forEach { message ->
            assertIs<EngineFailure.RateLimited>(piProviderFailure(message, "zai"))
        }
    }

    @Test
    fun `settled message error uses its native provider for coding plan diagnosis`() = runTest {
        val fixture = fixture(this)
        fixture.runningTurn()
        fixture.connection.event(
            record(
                """{"type":"message_end","message":{"role":"assistant","provider":"zai","content":[],
                "stopReason":"error","errorMessage":"429: {\"error\":{\"code\":\"1309\"}}"}}""",
            ),
        )
        assertIs<ActiveSessionState.Running>(fixture.session.state.value)
        fixture.connection.event(record("""{"type":"agent_settled"}"""))
        val ready = assertIs<ActiveSessionState.Ready>(fixture.session.state.value)
        assertIs<EngineFailure.QuotaExceeded>(assertIs<TurnOutcome.Failed>(ready.lastTurn?.outcome).failure)
        fixture.session.shutdown()
    }

    @Test
    fun `coding plan diagnostics expose neither provider body nor parser details`() {
        val records = mutableListOf<Pair<Throwable?, String>>()
        Log.init(isDebug = false, sinks = listOf(LogSink { _, _, error, message -> records += error to message }))
        try {
            listOf("1113", "1311", "1313", "1315").forEach { code ->
                val failure = piProviderFailure(
                    """429: {"code":"$code","message":"private-key private-prompt private-header"}""",
                    "zai",
                )
                assertFalse("private-" in failure.toString())
            }
            piProviderFailure("""429: {"code":"1113","message":"private-key private-prompt"""", "zai")
            assertTrue(records.isNotEmpty())
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
