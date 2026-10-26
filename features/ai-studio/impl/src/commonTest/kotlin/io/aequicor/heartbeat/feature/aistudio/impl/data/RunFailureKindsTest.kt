package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailure
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.LimitScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aistudio.impl.domain.RunFailureKind
import io.aequicor.heartbeat.feature.aistudio.screenmodel.FailureUi
import kotlin.test.Test
import kotlin.test.assertEquals

class RunFailureKindsTest {
    @Test
    fun `engine failures map to user facing classes`() {
        assertEquals(RunFailureKind.Quota, EngineFailure.QuotaExceeded(LimitScope.Unknown).toRunFailureKind())
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
        assertEquals(
            RunFailureKind.ServiceUnavailable,
            EngineFailure.Transport(TransportFailureReason.ServiceUnavailable).toRunFailureKind(),
        )
        assertEquals(RunFailureKind.Unknown, EngineFailure.Session(SessionFailureReason.Busy).toRunFailureKind())
        assertEquals(RunFailureKind.Unknown, null.toRunFailureKind())
    }

    @Test
    fun `missing local credentials are not reported as provider rejection`() {
        listOf(AuthFailureReason.NotAuthenticated, AuthFailureReason.SourceUnavailable).forEach { reason ->
            assertEquals(
                RunFailureKind.CredentialsMissing,
                EngineFailure.Authentication(AuthFailure(reason)).toRunFailureKind(),
            )
        }
        listOf(AuthFailureReason.AuthMismatch, AuthFailureReason.SourceChanged).forEach { reason ->
            assertEquals(
                RunFailureKind.ConnectionChanged,
                EngineFailure.Authentication(AuthFailure(reason)).toRunFailureKind(),
            )
        }
        listOf(AuthFailureReason.CredentialsExpired, AuthFailureReason.CredentialsRejected).forEach { reason ->
            assertEquals(
                RunFailureKind.Authentication,
                EngineFailure.Authentication(AuthFailure(reason)).toRunFailureKind(),
            )
        }
    }

    @Test
    fun `known connection failures retain distinct recovery guidance`() {
        assertEquals(
            RunFailureKind.Access,
            EngineFailure.Access(AccessFailureReason.ModelAccessDenied).toRunFailureKind(),
        )
        assertEquals(
            RunFailureKind.RequestRejected,
            EngineFailure.Request(RequestFailureReason.Invalid).toRunFailureKind(),
        )
        assertEquals(
            RunFailureKind.EngineUnavailable,
            EngineFailure.Engine(EngineFailureReason.RequirementsNotMet).toRunFailureKind(),
        )
        assertEquals(
            RunFailureKind.Unknown,
            EngineFailure.Request(RequestFailureReason.OutcomeUnknown).toRunFailureKind(),
        )
    }

    /** The screen maps a kind by name (`FailureUi.valueOf`): a kind added to one enum only fails at runtime. */
    @Test
    fun `every run failure kind has a screen class of the same name`() {
        assertEquals(RunFailureKind.entries.map { it.name }, FailureUi.entries.map { it.name })
    }
}
