package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.executor.clients.LLMClientException
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.ktor.client.network.sockets.ConnectTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class KoogFailureTest {
    @Test
    fun nativeResponseBodyAndCauseNeverEscapeSanitization() {
        val native = KoogHttpClientException(
            statusCode = 401,
            errorBody = "private credential and prompt",
            cause = IllegalStateException("private"),
        )
        val safe = native.sanitized()
        assertEquals(
            AuthFailureReason.CredentialsRejected,
            assertIs<EngineFailure.Authentication>(safe.failure).reason.reason,
        )
        assertEquals("auth.CredentialsRejected", safe.message)
        assertNull(safe.cause)
    }

    @Test
    fun rateLimitIsNotGuessedToBeExpiredCredentials() {
        val safe = KoogHttpClientException(statusCode = 429, errorBody = "private").sanitized()
        assertIs<EngineFailure.RateLimited>(safe.failure)
        assertIs<EngineFailure.Unknown>(IllegalArgumentException("private").sanitized().failure)
    }

    @Test
    fun sdkWrapperPreservesStatusWithoutExposingNativeMessages() {
        val native = LLMClientException(
            "private",
            cause = KoogHttpClientException(statusCode = 429, errorBody = "private"),
        )
        val safe = native.sanitized()
        assertIs<EngineFailure.RateLimited>(safe.failure)
        assertNull(safe.cause)
    }

    @Test
    fun `network failures without a status are transport failures`() {
        val refused = KoogHttpClientException(statusCode = null, cause = IOException("private"))
        assertEquals(TransportFailureReason.NetworkUnavailable, refused.sanitized().transport())
        val timeout = KoogHttpClientException(statusCode = null, cause = ConnectTimeoutException("private"))
        assertEquals(TransportFailureReason.Timeout, timeout.sanitized().transport())
    }

    @Test
    fun `provider error codes distinguish quota and context limits`() {
        val quota = KoogHttpClientException(statusCode = 429, errorBody = """{"code":"insufficient_quota"}""")
        assertIs<EngineFailure.QuotaExceeded>(quota.sanitized().failure)
        val context = KoogHttpClientException(statusCode = 400, errorBody = """{"code":"context_length_exceeded"}""")
        assertIs<EngineFailure.ContextLimitExceeded>(context.sanitized().failure)
        assertIs<EngineFailure.ContextLimitExceeded>(KoogHttpClientException(statusCode = 413).sanitized().failure)
    }

    @Test
    fun `access and server statuses map to their causes`() {
        assertEquals(
            AccessFailureReason.ModelAccessDenied,
            assertIs<EngineFailure.Access>(KoogHttpClientException(statusCode = 403).sanitized().failure).reason,
        )
        assertEquals(
            TransportFailureReason.ServiceUnavailable,
            KoogHttpClientException(statusCode = 503).sanitized().transport(),
        )
    }

    @Test
    fun `koogResult returns sanitized failure and keeps engine failures`() = runTest {
        val failure = koogResult { throw KoogHttpClientException(statusCode = 401) }.exceptionOrNull()
        assertIs<EngineFailure.Authentication>(assertIs<EngineException>(failure).failure)
        val busy = EngineFailure.Session(SessionFailureReason.Busy)
        assertEquals(busy, (koogResult { fail(busy) }.exceptionOrNull() as EngineException).failure)
    }

    private fun EngineException.transport() = assertIs<EngineFailure.Transport>(failure).reason

    @Test
    fun cancellationIsPropagatedUnchanged() = runTest {
        val cancellation = CancellationException("cancel")
        val caught = kotlin.test.assertFailsWith<CancellationException> { koogCall { throw cancellation } }
        assertSame(cancellation, caught)
    }
}
