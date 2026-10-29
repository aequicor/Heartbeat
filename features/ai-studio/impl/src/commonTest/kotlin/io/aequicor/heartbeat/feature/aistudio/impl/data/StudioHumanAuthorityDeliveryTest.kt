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
    fun `automatic native answers repeat with complete context and all tool barriers before delivery`() = runTest {
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
            active.expectAnswer = {
                assertEquals((index + 1) * 2, revoked.size)
                assertEquals(original, readStudioQuestionnaireHumanContext(store, "chat"))
            }
            authority.respondForAutomaticAnswer(active.request.toStudio("chat"), "submit", AUTOMATIC)
            active.nextQuestion()
        }
        assertEquals(listOf(active.ref, RECORDED, active.ref, RECORDED), revoked)
        assertEquals(2, active.decisions.size)
        assertEquals(original, readStudioQuestionnaireHumanContext(store, "chat"))
    }

    @Test
    fun `manual native answer closes context retained by earlier automatic answer`() = runTest {
        val store = deliveryStore()
        val active = QuestionnaireAuthoritySession()
        val authority = StudioQuestionAuthority(store, Mutex(), lazyOf(DELIVERY_TOOLS)) { active }
        authority.respondForAutomaticAnswer(active.request.toStudio("chat"), "submit", AUTOMATIC)
        active.nextQuestion()
        active.expectAnswer = {
            assertEquals(StudioQuestionnaireHumanContext.Incomplete, readStudioQuestionnaireHumanContext(store, "chat"))
            assertEquals(TrustedUserInstructions.Incomplete, readStudioUserInstructions(store, "chat"))
        }
        authority.respond(active.request.toStudio("chat"), "submit", StudioPermissionAnswer.Text("Avoid core too"))
        assertEquals(2, active.decisions.size)
    }

    @Test
    fun `failed live barrier prevents automatic native delivery without restoring tool authority`() = runTest {
        val store = deliveryStore()
        val active = QuestionnaireAuthoritySession()
        val tools = object : ProfileAgentTools by NoAgentTools {
            override suspend fun invalidateUserInstructions(session: SessionRef) {
                error("Live invalidation failed")
            }
        }
        val authority = StudioQuestionAuthority(store, Mutex(), lazyOf(tools)) { active }
        assertFailsWith<IllegalStateException> {
            authority.respondForAutomaticAnswer(active.request.toStudio("chat"), "submit", AUTOMATIC)
        }
        assertEquals(emptyList(), active.decisions)
        assertEquals(TrustedUserInstructions.Incomplete, readStudioUserInstructions(store, "chat"))
        assertIs<StudioQuestionnaireHumanContext.Complete>(readStudioQuestionnaireHumanContext(store, "chat"))
    }

    @Test
    fun `stale automatic provenance cannot revoke authority or deliver an answer`() = runTest {
        val store = deliveryStore()
        val active = QuestionnaireAuthoritySession()
        val authority = StudioQuestionAuthority(store, Mutex(), lazyOf(DELIVERY_TOOLS)) { active }
        val permission = active.request.toStudio("chat")
        val origin = StudioPermissionAnswerOrigin.NativeAutomatic(
            permission,
            isAuthorityCurrent = { false },
            isCurrent = { true },
        )
        assertFailsWith<IllegalStateException> { authority.respond(permission, "submit", AUTOMATIC, origin) }
        assertEquals(emptyList(), active.decisions)
        assertIs<TrustedUserInstructions.Complete>(readStudioUserInstructions(store, "chat"))
    }

    @Test
    fun `loss of eligibility during revocation prevents delivery without restoring authority`() = runTest {
        val store = deliveryStore()
        val active = QuestionnaireAuthoritySession()
        val authority = StudioQuestionAuthority(store, Mutex(), lazyOf(DELIVERY_TOOLS)) { active }
        val permission = active.request.toStudio("chat")
        val origin = StudioPermissionAnswerOrigin.NativeAutomatic(
            permission,
            isAuthorityCurrent = { true },
            isCurrent = { readStudioUserInstructions(store, "chat") is TrustedUserInstructions.Complete },
        )
        assertFailsWith<IllegalStateException> { authority.respond(permission, "submit", AUTOMATIC, origin) }
        assertEquals(emptyList(), active.decisions)
        assertEquals(TrustedUserInstructions.Incomplete, readStudioUserInstructions(store, "chat"))
        assertIs<StudioQuestionnaireHumanContext.Complete>(readStudioQuestionnaireHumanContext(store, "chat"))
    }

    @Test
    fun `stale automatic answer cannot revoke or answer a replacement native turn`() = runTest {
        val store = deliveryStore()
        val active = QuestionnaireAuthoritySession()
        val authority = StudioQuestionAuthority(store, Mutex(), lazyOf(DELIVERY_TOOLS)) { active }
        val stale = active.request.toStudio("chat")
        active.nextQuestion()
        assertFailsWith<IllegalArgumentException> {
            authority.respondForAutomaticAnswer(stale, "submit", AUTOMATIC)
        }
        assertEquals(emptyList(), active.decisions)
        assertIs<TrustedUserInstructions.Complete>(readStudioUserInstructions(store, "chat"))
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
