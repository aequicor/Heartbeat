package io.aequicor.heartbeat.feature.aistudio.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class AiStudioCreationMachineTest {
    private val settings = RunSettings("model", ReasoningEffort.High, ApprovalMode.Ask)
    private val home = AiStudioState.Ready(listOf(StudioPane(0)), 0, settings)

    @Test
    fun `new session abandons creation and a second submit cannot receive its results`() {
        val (creating, first) = submit(home, 0, "First")
        val replaced = advance(creating, AiStudioIntent.Public.NewSession("other-project"))
        assertStale(replaced, first)
        val (secondCreating, second) = submit(replaced, 0, "Second")
        assertNotEquals(first.requestId, second.requestId)
        assertEquals("other-project", second.projectId)
        assertStale(secondCreating, first)
        assertCreated(secondCreating, second, "second-session")
    }

    @Test
    fun `opening an existing chat abandons creation without marking a hidden session running`() {
        val (creating, first) = submit(home, 0, "First")
        val opened = advance(creating, AiStudioIntent.Public.OpenSession("existing"))
        assertEquals("existing", opened.panes.single().sessionId)
        assertStale(opened, first)
    }

    @Test
    fun `closing and reusing a pane id cannot adopt the old create results`() {
        val split = advance(home, AiStudioIntent.Public.OpenBeside(null))
        val (creating, first) = submit(split, 1, "First")
        val closed = advance(creating, AiStudioIntent.Public.ClosePane(1))
        assertStale(closed, first)
        val reopened = advance(closed, AiStudioIntent.Public.OpenBeside(null))
        assertEquals(first.paneId, reopened.focusedPaneId)
        assertStale(reopened, first)
        val (secondCreating, second) = submit(reopened, 1, "Second")
        assertNotEquals(first.requestId, second.requestId)
        assertStale(secondCreating, first)
        assertCreated(secondCreating, second, "second-session")
    }

    @Test
    fun `open beside replacing a pending pane abandons its request`() {
        val split = advance(home, AiStudioIntent.Public.OpenBeside(null))
        val (creating, first) = submit(split, 0, "First")
        val focused = advance(creating, AiStudioIntent.Public.FocusPane(1))
        val replaced = advance(focused, AiStudioIntent.Public.OpenBeside("existing"))
        assertEquals("existing", replaced.panes.first().sessionId)
        assertStale(replaced, first)
    }

    @Test
    fun `independent panes complete in reverse order with their captured prompts and settings`() {
        val split = advance(home, AiStudioIntent.Public.OpenBeside(null))
        val (firstCreating, first) = submit(split, 0, "First")
        val updated = advance(firstCreating, AiStudioIntent.Public.UpdateSettings(settings.copy(modelId = "other")))
        val (bothCreating, second) = submit(updated, 1, "Second")
        assertNotEquals(first.requestId, second.requestId)
        val secondCreated = assertCreated(bothCreating, second, "second-session")
        val bothCreated = assertCreated(secondCreated, first, "first-session")
        assertEquals(setOf("first-session", "second-session"), bothCreated.running)
    }

    @Test
    fun `focusing another pane preserves creation and keeps the focus when creation completes`() {
        val split = advance(home, AiStudioIntent.Public.OpenBeside(null))
        val (creating, first) = submit(split, 0, "First")
        val focused = advance(creating, AiStudioIntent.Public.FocusPane(1))
        val completed = assertCreated(focused, first, "first-session")
        assertEquals(1, completed.focusedPaneId)
    }

    @Test
    fun `a consumed success cannot start a duplicate run or later restore its prompt`() {
        val (creating, request) = submit(home, 0, "First")
        val completed = assertCreated(creating, request, "first-session")
        assertStale(completed, request)
    }

    @Test
    fun `only the pending failure restores a draft and retry gets a fresh token`() {
        val (creating, request) = submit(home, 0, "First")
        val failure = AiStudioIntent.Internal.CreateFailed(request.paneId, request.prompt, request.requestId)
        val failed = creating.copy(panes = creating.panes.map { it.copy(isCreating = false) })
        AiStudioMachineSpec.assertTransition(
            from = creating,
            intent = failure,
            to = failed,
            outputs = listOf(AiStudioOutput.SubmitFailed(request.paneId, request.prompt, request.requestId)),
        )
        assertStale(failed, request)
        val (retrying, retry) = submit(failed, 0, "Retry")
        assertNotEquals(request.requestId, retry.requestId)
        assertStale(retrying, request)
        assertCreated(retrying, retry, "retry-session")
    }

    @Test
    fun `create failure mapping preserves a noninitial request token`() {
        val request = AiStudioEffect.CreateSession(0, null, "First", settings, 37)
        assertEquals(
            AiStudioIntent.Internal.CreateFailed(0, "First", 37),
            AiStudioMachineSpec.onEffectFailure(request, IllegalStateException("Creation failed")),
        )
    }

    private fun submit(
        state: AiStudioState.Ready,
        paneId: Int,
        prompt: String,
    ): Pair<AiStudioState.Ready, AiStudioEffect.CreateSession> {
        val result = assertNotNull(AiStudioMachineSpec.resolve(state, AiStudioIntent.Public.Submit(paneId, prompt)))
        val request = assertIs<AiStudioEffect.CreateSession>(result.effects.single())
        return assertIs<AiStudioState.Ready>(result.to) to request
    }

    private fun advance(state: AiStudioState.Ready, intent: AiStudioIntent): AiStudioState.Ready =
        assertIs(assertNotNull(AiStudioMachineSpec.resolve(state, intent)).to)

    private fun assertStale(state: AiStudioState.Ready, request: AiStudioEffect.CreateSession) {
        AiStudioMachineSpec.assertIgnored(state, request.created("stale-session"))
        AiStudioMachineSpec.assertIgnored(
            state,
            AiStudioIntent.Internal.CreateFailed(request.paneId, request.prompt, request.requestId),
        )
    }

    private fun assertCreated(
        state: AiStudioState.Ready,
        request: AiStudioEffect.CreateSession,
        sessionId: String,
    ): AiStudioState.Ready {
        val expected = state.copy(
            panes = state.panes.map { if (it.id == request.paneId) StudioPane(it.id, sessionId = sessionId) else it },
            running = state.running + sessionId,
        )
        AiStudioMachineSpec.assertTransition(
            from = state,
            intent = request.created(sessionId),
            to = expected,
            effects = listOf(AiStudioEffect.Run(sessionId, request.prompt, request.settings)),
        )
        return expected
    }

    private fun AiStudioEffect.CreateSession.created(sessionId: String) =
        AiStudioIntent.Internal.SessionCreated(paneId, sessionId, prompt, settings, requestId)
}
