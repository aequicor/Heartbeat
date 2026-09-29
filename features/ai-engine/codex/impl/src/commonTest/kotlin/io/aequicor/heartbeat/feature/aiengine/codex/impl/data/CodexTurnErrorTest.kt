package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.LimitScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CodexTurnErrorTest {
    private val binding = EngineBindingId("codex-binding")

    private fun unit(kind: String) = json(
        "message" to "You've hit your usage limit".json(),
        "codexErrorInfo" to kind.json(),
    )

    private fun tagged(kind: String) = json(
        "message" to "failed".json(),
        "codexErrorInfo" to json(kind to json("httpStatusCode" to JsonNull)),
    )

    @Test
    fun `usage limit maps to binding quota`() {
        assertEquals(
            EngineFailure.QuotaExceeded(LimitScope.Binding(binding)),
            codexTurnFailure(unit("usageLimitExceeded"), binding),
        )
    }

    @Test
    fun `context window maps to context limit`() {
        assertIs<EngineFailure.ContextLimitExceeded>(codexTurnFailure(unit("contextWindowExceeded"), binding))
    }

    @Test
    fun `unauthorized maps to rejected credentials`() {
        val failure = assertIs<EngineFailure.Authentication>(codexTurnFailure(unit("unauthorized"), binding))
        assertEquals(AuthFailureReason.CredentialsRejected, failure.reason.reason)
    }

    @Test
    fun `tagged connection failures map to transport`() {
        assertEquals(
            EngineFailure.Transport(TransportFailureReason.NetworkUnavailable),
            codexTurnFailure(tagged("httpConnectionFailed"), binding),
        )
        assertEquals(
            EngineFailure.Transport(TransportFailureReason.ServiceUnavailable),
            codexTurnFailure(unit("serverOverloaded"), binding),
        )
    }

    @Test
    fun `missing or unknown info stays unknown`() {
        assertIs<EngineFailure.Unknown>(codexTurnFailure(null, binding))
        assertIs<EngineFailure.Unknown>(codexTurnFailure(JsonObject(emptyMap()), binding))
        assertIs<EngineFailure.Unknown>(codexTurnFailure(unit("sandboxError"), binding))
    }
}
