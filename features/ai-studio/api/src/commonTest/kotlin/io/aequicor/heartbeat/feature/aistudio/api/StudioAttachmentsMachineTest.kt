package io.aequicor.heartbeat.feature.aistudio.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import kotlin.test.Test
import kotlin.test.assertEquals

class StudioAttachmentsMachineTest {
    private val settings = RunSettings("model", ReasoningEffort.High, ApprovalMode.Ask)
    private val files = listOf(ResourceRef("attachment:image", "image/png"))
    private val ready = AiStudioState.Ready(listOf(StudioPane(0, sessionId = "chat")), 0, settings)

    @Test
    fun `an attachment-only message starts a native run with original resource references`() {
        AiStudioMachineSpec.assertTransition(
            from = ready,
            intent = AiStudioIntent.Public.Submit(0, "", files, "submit"),
            to = ready.copy(running = setOf("chat")),
            effects = listOf(AiStudioEffect.Run("chat", "", settings, files, 0, "submit")),
        )
        AiStudioMachineSpec.assertIgnored(ready, AiStudioIntent.Public.Submit(0, ""))
    }

    @Test
    fun `creating a chat retains resources and submission identity for its first run`() {
        val home = ready.copy(panes = listOf(StudioPane(0)))
        val creating = home.copy(
            panes = listOf(StudioPane(0, isCreating = true, createRequestId = 0)),
            nextCreateRequestId = 1,
        )
        AiStudioMachineSpec.assertTransition(
            from = home,
            intent = AiStudioIntent.Public.Submit(0, "", files, "first"),
            to = creating,
            effects = listOf(AiStudioEffect.CreateSession(0, null, "", settings, 0, files, "first")),
        )
        AiStudioMachineSpec.assertTransition(
            from = creating,
            intent = AiStudioIntent.Internal.SessionCreated(0, "chat", "", settings, 0, files, "first"),
            to = creating.copy(panes = listOf(StudioPane(0, sessionId = "chat")), running = setOf("chat")),
            effects = listOf(AiStudioEffect.Run("chat", "", settings, files, 0, "first")),
            outputs = listOf(AiStudioOutput.SubmitPrepared("first", "chat")),
        )
    }

    @Test
    fun `native acceptance clears only the correlated submission and rejection ends provisional running`() {
        val running = ready.copy(running = setOf("chat"))
        AiStudioMachineSpec.assertTransition(
            from = running,
            intent = AiStudioIntent.Internal.RunAccepted(0, "submit", "chat"),
            to = running,
            outputs = listOf(AiStudioOutput.SubmitAccepted(0, "submit", "chat")),
        )
        AiStudioMachineSpec.assertTransition(
            from = running,
            intent = AiStudioIntent.Internal.RunRejected(0, "submit", "chat"),
            to = ready,
            outputs = listOf(AiStudioOutput.SubmitRejected(0, "submit", "chat")),
        )
        assertEquals(
            AiStudioIntent.Internal.RunRejected(0, "submit", "chat"),
            AiStudioMachineSpec.onEffectFailure(
                AiStudioEffect.Run("chat", "", settings, files, 0, "submit"),
                IllegalArgumentException("Rejected"),
            ),
        )
    }

    @Test
    fun `navigating during creation leaves the draft associated with its created session without running a turn`() {
        AiStudioMachineSpec.assertTransition(
            from = ready,
            intent = AiStudioIntent.Internal.SessionCreated(0, "created", "Question", settings, 0, files, "submit"),
            to = ready,
            outputs = listOf(AiStudioOutput.SubmitRejected(0, "submit", "created")),
        )
    }
}
