package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionInput
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestsPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionAnswer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StudioPermissionAuthorityTest {
    @Test
    fun `structured native answers wait for authority revocation before delivery`() = runTest {
        val fixture = PermissionAuthoritySession()
        val revoked = CompletableDeferred<Unit>()
        val delivery = async {
            fixture.respondStudioPermission("question", "submit", StudioPermissionAnswer.Text("Never read core")) {
                revoked.await()
            }
        }
        runCurrent()
        assertTrue(fixture.decisions.isEmpty())
        revoked.complete(Unit)
        delivery.await()
        assertEquals(1, fixture.decisions.size)
    }

    @Test
    fun `failed authority revocation prevents native answer delivery`() = runTest {
        val fixture = PermissionAuthoritySession()
        assertFailsWith<IllegalStateException> {
            fixture.respondStudioPermission("question", "submit", StudioPermissionAnswer.Text("Never read core")) {
                error("Durable or live revocation failed")
            }
        }
        assertTrue(fixture.decisions.isEmpty())
    }

    @Test
    fun `cancellation during authority revocation prevents native answer delivery`() = runTest {
        val fixture = PermissionAuthoritySession()
        val revoked = CompletableDeferred<Unit>()
        val delivery = async {
            fixture.respondStudioPermission("question", "submit", StudioPermissionAnswer.Text("Never read core")) {
                revoked.await()
            }
        }
        runCurrent()
        delivery.cancel()
        revoked.complete(Unit)
        assertFailsWith<CancellationException> { delivery.await() }
        assertTrue(fixture.decisions.isEmpty())
    }

    @Test
    fun `ordinary tool approval does not revoke original human authority`() = runTest {
        val fixture = PermissionAuthoritySession(input = null)
        fixture.respondStudioPermission("question", "submit", null) { error("Not a questionnaire answer") }
        assertEquals(1, fixture.decisions.size)
    }

    @Test
    fun `skipped native questionnaire answers do not revoke authority`() = runTest {
        val fixture = PermissionAuthoritySession(isSkip = true)
        fixture.respondStudioPermission("question", "submit", null) { error("A skipped question has no answer") }
        assertEquals(1, fixture.decisions.size)
    }

    @Test
    fun `questionnaire button decisions revoke authority even without structured answer text`() = runTest {
        val fixture = PermissionAuthoritySession()
        var revoked = false
        fixture.respondStudioPermission("question", "submit", null) { revoked = true }
        assertTrue(revoked)
        assertEquals(1, fixture.decisions.size)
    }
}

private class PermissionAuthoritySession(
    input: PermissionInput? = PermissionInput.FreeText(),
    isSkip: Boolean = false,
) : ActiveSession {
    private val turn = Turn(TurnId("turn"), null, EngineTarget(EngineId("engine"), EngineBindingId("b"), ModelId("m")))
    private val request = PermissionRequest(
        PermissionRequestId("question"),
        turn.id,
        "Generated title never becomes authority",
        listOf(PermissionOption(PermissionOptionId("submit"), "Generated choice", isSkip = isSkip)),
        input = input,
    )
    val decisions = mutableListOf<PermissionDecision>()
    override val ref = SessionRef(EngineId("engine"), SessionSourceId("local"), "native")
    override val route: ExecutionRoute get() = error("Not used by permission delivery")
    override val state = MutableStateFlow<ActiveSessionState>(
        ActiveSessionState.AwaitingUserAction(turn, listOf(request)),
    )
    override val features = object : EngineFeatures {
        @Suppress("UNCHECKED_CAST") // This fake exposes only the RequestsPermissions contract.
        override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> =
            if (key == RequestsPermissions) {
                FeatureAccess.Available(
                    object : RequestsPermissions {
                        override suspend fun respond(decision: PermissionDecision) {
                            decisions += decision
                            state.value = ActiveSessionState.Running(turn.copy(resolvedPermissions = setOf(request.id)))
                        }
                    } as F,
                )
            } else {
                FeatureAccess.Unsupported
            }
    }
    override suspend fun close() = Unit
}
