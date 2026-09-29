package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailure
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.LimitScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aistudio.impl.domain.RunFailureKind
import kotlin.test.Test
import kotlin.test.assertEquals

class RunFailureKindsTest {
    @Test
    fun `engine failures map to user facing classes`() {
        assertEquals(RunFailureKind.Limit, EngineFailure.QuotaExceeded(LimitScope.Unknown).toRunFailureKind())
        assertEquals(RunFailureKind.Limit, EngineFailure.RateLimited(LimitScope.Unknown).toRunFailureKind())
        assertEquals(RunFailureKind.Context, EngineFailure.ContextLimitExceeded().toRunFailureKind())
        assertEquals(
            RunFailureKind.Authentication,
            EngineFailure.Authentication(AuthFailure(AuthFailureReason.CredentialsRejected)).toRunFailureKind(),
        )
        assertEquals(
            RunFailureKind.Network,
            EngineFailure.Transport(TransportFailureReason.NetworkUnavailable).toRunFailureKind(),
        )
        assertEquals(RunFailureKind.Unknown, EngineFailure.Session(SessionFailureReason.Busy).toRunFailureKind())
        assertEquals(RunFailureKind.Unknown, null.toRunFailureKind())
    }
}
