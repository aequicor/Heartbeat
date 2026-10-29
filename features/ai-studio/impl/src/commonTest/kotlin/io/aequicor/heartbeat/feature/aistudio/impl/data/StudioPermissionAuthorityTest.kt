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
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioPermissionAnswerOrigin
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioPermissionResponder
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
    fun `runtime without provenance support rejects automatic answers`() = runTest {
        val responder = object : StudioPermissionResponder {
            override suspend fun respond(
                sessionId: String,
                requestId: String,
                optionId: String,
                answer: StudioPermissionAnswer?,
            ) = error("Must not fall back to uncaptured delivery")
        }
        val permission = PermissionAuthoritySession().request.toStudio("studio")
        val origin = StudioPermissionAnswerOrigin.NativeAutomatic(permission, { true }, { true })
        val failure = assertFailsWith<IllegalStateException> {
            responder.respond(permission, "submit", StudioPermissionAnswer.Text("answer"), origin)
        }
        assertEquals("Automatic answer provenance is unavailable", failure.message)
    }

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
    fun `automatic answer checks authority before revoke and only eligibility afterwards`() = runTest {
        val fixture = PermissionAuthoritySession()
        val permission = fixture.request.toStudio("studio")
        val events = mutableListOf<String>()
        var revoked = false
        val origin = StudioPermissionAnswerOrigin.NativeAutomatic(
            permission,
            isAuthorityCurrent = {
                events += "authority"
                !revoked
            },
            isCurrent = {
                events += "eligibility"
                true
            },
        )
        fixture.respondStudioPermission(permission, "submit", StudioPermissionAnswer.Text("answer"), origin) {
            events += "revoke"
            revoked = true
        }
        assertEquals(listOf("eligibility", "authority", "revoke", "eligibility"), events)
        assertEquals(1, fixture.decisions.size)
    }

    @Test
    fun `automatic provenance cannot approve ordinary permissions`() = runTest {
        val fixture = PermissionAuthoritySession(input = null)
        val permission = fixture.request.toStudio("studio")
        val origin = StudioPermissionAnswerOrigin.NativeAutomatic(permission, { true }, { true })
        var revoked = false
        assertFailsWith<IllegalStateException> {
            fixture.respondStudioPermission(permission, "submit", StudioPermissionAnswer.Text("yes"), origin) {
                revoked = true
            }
        }
        assertTrue(!revoked)
        assertTrue(fixture.decisions.isEmpty())
    }

    @Test
    fun `automatic provenance cannot be rebound to another captured permission`() = runTest {
        val fixture = PermissionAuthoritySession()
        val permission = fixture.request.toStudio("studio")
        val origin = StudioPermissionAnswerOrigin.NativeAutomatic(
            permission.copy(sessionId = "other"),
            { true },
            { true },
        )
        assertFailsWith<IllegalArgumentException> {
            fixture.respondStudioPermission(permission, "submit", StudioPermissionAnswer.Text("answer"), origin) {
                error("Must reject before revoking")
            }
        }
        assertTrue(fixture.decisions.isEmpty())
    }

    @Test
    fun `automatic provenance requires a captured native turn`() = runTest {
        val fixture = PermissionAuthoritySession()
        val origin = StudioPermissionAnswerOrigin.NativeAutomatic(
            fixture.request.toStudio("studio"),
            { true },
            { true },
        )
        assertFailsWith<IllegalStateException> {
            fixture.respondStudioPermission(
                "question",
                "submit",
                StudioPermissionAnswer.Text("answer"),
                origin = origin,
            ) {
                error("Must reject before revoking")
            }
        }
        assertTrue(fixture.decisions.isEmpty())
    }

    @Test
    fun `stale automatic authority fails before revocation`() = runTest {
        val fixture = PermissionAuthoritySession()
        val permission = fixture.request.toStudio("studio")
        val origin = StudioPermissionAnswerOrigin.NativeAutomatic(permission, { false }, { true })
        var revoked = false
        assertFailsWith<IllegalStateException> {
            fixture.respondStudioPermission(permission, "submit", StudioPermissionAnswer.Text("answer"), origin) {
                revoked = true
            }
        }
        assertTrue(!revoked)
        assertTrue(fixture.decisions.isEmpty())
    }

    @Test
    fun `automatic eligibility lost during revoke prevents delivery`() = runTest {
        val fixture = PermissionAuthoritySession()
        val permission = fixture.request.toStudio("studio")
        var eligible = true
        val origin = StudioPermissionAnswerOrigin.NativeAutomatic(permission, { true }, { eligible })
        assertFailsWith<IllegalStateException> {
            fixture.respondStudioPermission(permission, "submit", StudioPermissionAnswer.Text("answer"), origin) {
                eligible = false
            }
        }
        assertTrue(fixture.decisions.isEmpty())
    }

    @Test
    fun `reused request changed during authority check cannot revoke or deliver`() = runTest {
        val fixture = PermissionAuthoritySession()
        val permission = fixture.request.toStudio("studio")
        val origin = StudioPermissionAnswerOrigin.NativeAutomatic(permission, {
            fixture.replaceRequest()
            true
        }, { true })
        var revoked = false
        assertFailsWith<IllegalStateException> {
            fixture.respondStudioPermission(permission, "submit", StudioPermissionAnswer.Text("answer"), origin) {
                revoked = true
            }
        }
        assertTrue(!revoked)
        assertTrue(fixture.decisions.isEmpty())
    }

    @Test
    fun `reused request changed during revoke cannot receive manual answer`() = runTest {
        val fixture = PermissionAuthoritySession()
        assertFailsWith<IllegalStateException> {
            fixture.respondStudioPermission("question", "submit", StudioPermissionAnswer.Text("answer")) {
                fixture.replaceRequest()
            }
        }
        assertTrue(fixture.decisions.isEmpty())
    }

    @Test
    fun `cancellation during automatic post revoke check propagates without delivery`() = runTest {
        val fixture = PermissionAuthoritySession()
        val permission = fixture.request.toStudio("studio")
        var revoked = false
        val origin = StudioPermissionAnswerOrigin.NativeAutomatic(permission, { true }, {
            if (revoked) throw CancellationException("Cancelled after revoke")
            true
        })
        assertFailsWith<CancellationException> {
            fixture.respondStudioPermission(permission, "submit", StudioPermissionAnswer.Text("answer"), origin) {
                revoked = true
            }
        }
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
    val request = PermissionRequest(
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
    fun replaceRequest() {
        state.value = ActiveSessionState.AwaitingUserAction(turn, listOf(request.copy(title = "Replacement question")))
    }
    override suspend fun close() = Unit
}
