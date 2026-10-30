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
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionInput
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestsPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustedUserInstructions
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermission
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionAnswer
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioPermissionAnswerOrigin
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioQuestionnaireHumanContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Instant

class StudioHumanAuthorityDeliveryTest {
    @Test
    fun `automatic native answers are rejected before revocation and delivery on every turn`() = runTest {
        val store = deliveryStore()
        val active = QuestionnaireAuthoritySession()
        val original = readStudioQuestionnaireHumanContext(store, "chat")
        val instructions = readStudioUserInstructions(store, "chat")
        val revoked = mutableListOf<SessionRef>()
        val tools = object : ProfileAgentTools by NoAgentTools {
            override suspend fun invalidateUserInstructions(session: SessionRef) {
                revoked += session
            }
        }
        val authority = StudioQuestionAuthority(store, Mutex(), lazyOf(tools)) { active }
        repeat(2) {
            val pending = active.state.value
            val failure = assertFailsWith<IllegalStateException> {
                authority.respondForAutomaticAnswer(active.request.toStudio("chat"), "submit", AUTOMATIC)
            }
            assertEquals("Native automatic answers require protocol provenance support", failure.message)
            assertEquals(emptyList(), revoked)
            assertEquals(emptyList(), active.decisions)
            assertEquals(pending, active.state.value)
            assertEquals(instructions, readStudioUserInstructions(store, "chat"))
            assertEquals(original, readStudioQuestionnaireHumanContext(store, "chat"))
            active.nextQuestion()
        }
    }

    @Test
    fun `explicit automatic revocation repeats all live barriers and preserves only human context`() = runTest {
        val store = deliveryStore()
        val active = QuestionnaireAuthoritySession()
        val original = readStudioQuestionnaireHumanContext(store, "chat")
        val revoked = mutableListOf<SessionRef>()
        val tools = object : ProfileAgentTools by NoAgentTools {
            override suspend fun invalidateUserInstructions(session: SessionRef) {
                assertEquals(TrustedUserInstructions.Incomplete, readStudioUserInstructions(store, "chat"))
                assertEquals(original, readStudioQuestionnaireHumanContext(store, "chat"))
                revoked += session
            }
        }
        val authority = StudioQuestionAuthority(store, Mutex(), lazyOf(tools)) { active }
        repeat(2) { index ->
            authority.invalidateForAutomaticAnswer("chat", active.ref)
            assertEquals((index + 1) * 2, revoked.size)
            assertEquals(TrustedUserInstructions.Incomplete, readStudioUserInstructions(store, "chat"))
            assertEquals(original, readStudioQuestionnaireHumanContext(store, "chat"))
        }
        assertEquals(listOf(active.ref, RECORDED, active.ref, RECORDED), revoked)
        assertEquals(emptyList(), active.decisions)
    }

    @Test
    fun `retained human context after revocation does not authorize automatic native delivery`() = runTest {
        val store = deliveryStore()
        val active = QuestionnaireAuthoritySession()
        val original = readStudioQuestionnaireHumanContext(store, "chat")
        val revoked = mutableListOf<SessionRef>()
        val tools = object : ProfileAgentTools by NoAgentTools {
            override suspend fun invalidateUserInstructions(session: SessionRef) {
                revoked += session
            }
        }
        val authority = StudioQuestionAuthority(store, Mutex(), lazyOf(tools)) { active }
        authority.invalidateForAutomaticAnswer("chat", active.ref)
        val pending = active.state.value
        val failure = assertFailsWith<IllegalStateException> {
            authority.respondForAutomaticAnswer(active.request.toStudio("chat"), "submit", AUTOMATIC)
        }
        assertEquals("Native automatic answers require protocol provenance support", failure.message)
        assertEquals(listOf(active.ref, RECORDED), revoked)
        assertEquals(emptyList(), active.decisions)
        assertEquals(pending, active.state.value)
        assertEquals(TrustedUserInstructions.Incomplete, readStudioUserInstructions(store, "chat"))
        assertEquals(original, readStudioQuestionnaireHumanContext(store, "chat"))
    }

    @Test
    fun `manual native answer closes retained context and completes all barriers before delivery`() = runTest {
        val store = deliveryStore()
        val active = QuestionnaireAuthoritySession()
        val revoked = mutableListOf<SessionRef>()
        val tools = object : ProfileAgentTools by NoAgentTools {
            override suspend fun invalidateUserInstructions(session: SessionRef) {
                assertEquals(TrustedUserInstructions.Incomplete, readStudioUserInstructions(store, "chat"))
                revoked += session
            }
        }
        val authority = StudioQuestionAuthority(store, Mutex(), lazyOf(tools)) { active }
        authority.invalidateForAutomaticAnswer("chat", active.ref)
        assertIs<StudioQuestionnaireHumanContext.Complete>(readStudioQuestionnaireHumanContext(store, "chat"))
        active.expectAnswer = {
            assertEquals(listOf(active.ref, RECORDED, active.ref, RECORDED), revoked)
            assertEquals(StudioQuestionnaireHumanContext.Incomplete, readStudioQuestionnaireHumanContext(store, "chat"))
            assertEquals(TrustedUserInstructions.Incomplete, readStudioUserInstructions(store, "chat"))
        }
        val answer = StudioPermissionAnswer.Text("Avoid core too")
        authority.respond(active.request.toStudio("chat"), "submit", answer)
        assertEquals(
            listOf(
                PermissionDecision(
                    active.request.turn,
                    active.request.id,
                    PermissionOptionId("submit"),
                    answer.toFacade(),
                ),
            ),
            active.decisions,
        )
        assertEquals(StudioQuestionnaireHumanContext.Incomplete, readStudioQuestionnaireHumanContext(store, "chat"))
        assertEquals(TrustedUserInstructions.Incomplete, readStudioUserInstructions(store, "chat"))
    }

    @Test
    fun `failed live barrier prevents manual native delivery without restoring authority`() = runTest {
        val store = deliveryStore()
        val active = QuestionnaireAuthoritySession()
        val revoked = mutableListOf<SessionRef>()
        val tools = object : ProfileAgentTools by NoAgentTools {
            override suspend fun invalidateUserInstructions(session: SessionRef) {
                revoked += session
                error("Live invalidation failed")
            }
        }
        val authority = StudioQuestionAuthority(store, Mutex(), lazyOf(tools)) { active }
        val pending = active.state.value
        val failure = assertFailsWith<IllegalStateException> {
            authority.respond(active.request.toStudio("chat"), "submit", StudioPermissionAnswer.Text("Avoid core too"))
        }
        assertEquals("Live invalidation failed", failure.message)
        assertEquals(listOf(active.ref), revoked)
        assertEquals(emptyList(), active.decisions)
        assertEquals(pending, active.state.value)
        assertEquals(TrustedUserInstructions.Incomplete, readStudioUserInstructions(store, "chat"))
        assertEquals(StudioQuestionnaireHumanContext.Incomplete, readStudioQuestionnaireHumanContext(store, "chat"))
    }

    @Test
    fun `failed explicit automatic revocation retains context without restoring tool authority`() = runTest {
        val store = deliveryStore()
        val active = QuestionnaireAuthoritySession()
        val original = readStudioQuestionnaireHumanContext(store, "chat")
        val revoked = mutableListOf<SessionRef>()
        val tools = object : ProfileAgentTools by NoAgentTools {
            override suspend fun invalidateUserInstructions(session: SessionRef) {
                revoked += session
                error("Live invalidation failed")
            }
        }
        val authority = StudioQuestionAuthority(store, Mutex(), lazyOf(tools)) { active }
        val failure = assertFailsWith<IllegalStateException> {
            authority.invalidateForAutomaticAnswer("chat", active.ref)
        }
        assertEquals("Live invalidation failed", failure.message)
        assertEquals(listOf(active.ref), revoked)
        assertEquals(emptyList(), active.decisions)
        assertEquals(TrustedUserInstructions.Incomplete, readStudioUserInstructions(store, "chat"))
        assertEquals(original, readStudioQuestionnaireHumanContext(store, "chat"))
    }

    @Test
    fun `stale automatic provenance cannot revoke authority or deliver an answer`() = runTest {
        val store = deliveryStore()
        val active = QuestionnaireAuthoritySession()
        val original = readStudioQuestionnaireHumanContext(store, "chat")
        val authority = StudioQuestionAuthority(store, Mutex(), lazyOf(DELIVERY_TOOLS)) { active }
        val permission = active.request.toStudio("chat")
        val origin = StudioPermissionAnswerOrigin.NativeAutomatic(
            permission,
            isAuthorityCurrent = { false },
            isCurrent = { true },
        )
        val failure = assertFailsWith<IllegalStateException> {
            authority.respond(permission, "submit", AUTOMATIC, origin)
        }
        assertEquals("Native automatic answers require protocol provenance support", failure.message)
        assertEquals(emptyList(), active.decisions)
        assertIs<TrustedUserInstructions.Complete>(readStudioUserInstructions(store, "chat"))
        assertEquals(original, readStudioQuestionnaireHumanContext(store, "chat"))
    }

    @Test
    fun `replacement native turn during manual revocation prevents delivery without restoring authority`() = runTest {
        val store = deliveryStore()
        val active = QuestionnaireAuthoritySession()
        val revoked = mutableListOf<SessionRef>()
        val tools = object : ProfileAgentTools by NoAgentTools {
            override suspend fun invalidateUserInstructions(session: SessionRef) {
                revoked += session
                if (session == active.ref) active.nextQuestion()
            }
        }
        val authority = StudioQuestionAuthority(store, Mutex(), lazyOf(tools)) { active }
        val permission = active.request.toStudio("chat")
        val failure = assertFailsWith<IllegalStateException> {
            authority.respond(permission, "submit", StudioPermissionAnswer.Text("Avoid core too"))
        }
        assertEquals("Permission changed during authority revocation", failure.message)
        assertEquals(listOf(active.ref, RECORDED), revoked)
        assertEquals(emptyList(), active.decisions)
        assertEquals(TrustedUserInstructions.Incomplete, readStudioUserInstructions(store, "chat"))
        assertEquals(StudioQuestionnaireHumanContext.Incomplete, readStudioQuestionnaireHumanContext(store, "chat"))
    }

    @Test
    fun `stale automatic answer is rejected before inspecting a replacement native turn`() = runTest {
        val store = deliveryStore()
        val active = QuestionnaireAuthoritySession()
        val original = readStudioQuestionnaireHumanContext(store, "chat")
        val authority = StudioQuestionAuthority(store, Mutex(), lazyOf(DELIVERY_TOOLS)) { active }
        val stale = active.request.toStudio("chat")
        active.nextQuestion()
        val pending = active.state.value
        val failure = assertFailsWith<IllegalStateException> {
            authority.respondForAutomaticAnswer(stale, "submit", AUTOMATIC)
        }
        assertEquals("Native automatic answers require protocol provenance support", failure.message)
        assertEquals(emptyList(), active.decisions)
        assertEquals(pending, active.state.value)
        assertIs<TrustedUserInstructions.Complete>(readStudioUserInstructions(store, "chat"))
        assertEquals(original, readStudioQuestionnaireHumanContext(store, "chat"))
    }

    @Test
    fun `stale manual answer cannot revoke or answer a replacement native turn`() = runTest {
        val store = deliveryStore()
        val active = QuestionnaireAuthoritySession()
        val original = readStudioQuestionnaireHumanContext(store, "chat")
        val authority = StudioQuestionAuthority(store, Mutex(), lazyOf(DELIVERY_TOOLS)) { active }
        val stale = active.request.toStudio("chat")
        active.nextQuestion()
        val pending = active.state.value
        assertFailsWith<IllegalArgumentException> {
            authority.respond(stale, "submit", StudioPermissionAnswer.Text("Avoid core too"))
        }
        assertEquals(emptyList(), active.decisions)
        assertEquals(pending, active.state.value)
        assertIs<TrustedUserInstructions.Complete>(readStudioUserInstructions(store, "chat"))
        assertEquals(original, readStudioQuestionnaireHumanContext(store, "chat"))
    }
}

private suspend fun StudioQuestionAuthority.respondForAutomaticAnswer(
    permission: StudioPermission,
    option: String,
    answer: StudioPermissionAnswer,
) = respond(
    permission,
    option,
    answer,
    StudioPermissionAnswerOrigin.NativeAutomatic(permission, isAuthorityCurrent = { true }, isCurrent = { true }),
)

private class QuestionnaireAuthoritySession : ActiveSession {
    private var generation = 0
    private fun turn() = Turn(
        TurnId("turn-$generation"),
        null,
        EngineTarget(EngineId("engine"), EngineBindingId("b"), ModelId("m")),
    )

    private fun pendingRequest() = PermissionRequest(
        PermissionRequestId("question"),
        turn().id,
        "Generated title must never enter human context",
        listOf(PermissionOption(PermissionOptionId("submit"), "Submit")),
        input = PermissionInput.FreeText(),
    )

    var request = pendingRequest()
        private set
    var expectAnswer: suspend () -> Unit = {}
    val decisions = mutableListOf<PermissionDecision>()
    override val ref = RECORDED.copy(nativeId = "current")
    override val route: ExecutionRoute get() = error("Unused")
    override val state = MutableStateFlow<ActiveSessionState>(
        ActiveSessionState.AwaitingUserAction(turn(), listOf(request)),
    )
    override val features = object : EngineFeatures {
        @Suppress("UNCHECKED_CAST") // The fixture exposes only the RequestsPermissions feature.
        override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> =
            if (key == RequestsPermissions) {
                FeatureAccess.Available(
                    object : RequestsPermissions {
                        override suspend fun respond(decision: PermissionDecision) {
                            expectAnswer()
                            decisions += decision
                            state.value = ActiveSessionState.Running(
                                turn().copy(resolvedPermissions = setOf(request.id)),
                            )
                        }
                    } as F,
                )
            } else {
                FeatureAccess.Unsupported
            }
    }

    fun nextQuestion() {
        generation++
        request = pendingRequest()
        state.value = ActiveSessionState.AwaitingUserAction(turn(), listOf(request))
    }

    override suspend fun close() = Unit
}

private suspend fun deliveryStore(): ChecklistTestStore = ChecklistTestStore(ChatSpec).apply {
    set(
        ChatsKey,
        listOf(
            StudioChatRecord(
                "chat",
                "Chat",
                Instant.fromEpochSeconds(0),
                ref = RECORDED,
                humanAuthority = StudioHumanAuthority().append(RequestId("human"), "Never read secrets"),
            ),
        ),
    )
}

private val RECORDED = SessionRef(EngineId("engine"), SessionSourceId("local"), "recorded")
private val AUTOMATIC = StudioPermissionAnswer.Text("Generated answer")
private val DELIVERY_TOOLS = object : ProfileAgentTools by NoAgentTools {
    override suspend fun invalidateUserInstructions(session: SessionRef) = Unit
}
