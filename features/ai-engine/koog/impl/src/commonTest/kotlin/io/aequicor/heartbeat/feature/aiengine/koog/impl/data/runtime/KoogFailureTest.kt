package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.executor.clients.LLMClientException
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
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
    fun cancellationIsPropagatedUnchanged() = runTest {
        val cancellation = CancellationException("cancel")
        val caught = kotlin.test.assertFailsWith<CancellationException> { koogCall { throw cancellation } }
        assertSame(cancellation, caught)
    }
}
