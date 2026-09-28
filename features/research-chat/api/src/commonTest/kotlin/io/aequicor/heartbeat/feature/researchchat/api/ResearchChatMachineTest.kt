package io.aequicor.heartbeat.feature.researchchat.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import kotlin.test.Test
import kotlin.test.assertEquals

class ResearchChatMachineTest {
    private val target = EngineTarget(EngineId("koog"), EngineBindingId("binding"), ModelId("model"))
    private val question = ResearchQuestion("q1")
    private val session = ResearchSession("s1", "", target, listOf(question))
    private val workspace = ResearchWorkspace(listOf(session))
    private val ready = ResearchChatState.Ready(target, workspace, session.id, question.id)

    @Test
    fun `opening loads once and disabled routes never enter ready`() {
        ResearchChatMachineSpec.assertTransition(
            ResearchChatState.Idle,
            ResearchChatIntent.Public.Start(target),
            ResearchChatState.Loading(target),
            effects = listOf(ResearchChatEffect.Load(target)),
        )
        ResearchChatMachineSpec.assertIgnored(
            ResearchChatState.Loading(target),
            ResearchChatIntent.Public.Start(target),
        )
        ResearchChatMachineSpec.assertTransition(
            ResearchChatState.Loading(target),
            ResearchChatIntent.Internal.Loaded(workspace, false),
            ResearchChatState.Disabled,
        )
    }

    @Test
    fun `loaded workspace observes profile updates and failed loading can retry`() {
        ResearchChatMachineSpec.assertTransition(
            ResearchChatState.Loading(target),
            ResearchChatIntent.Internal.Loaded(workspace, true),
            ready,
            effects = listOf(ResearchChatEffect.Observe),
        )
        ResearchChatMachineSpec.assertTransition(
            ResearchChatState.Loading(target),
            ResearchChatIntent.Internal.LoadFailed,
            ResearchChatState.Failed(target),
        )
        ResearchChatMachineSpec.assertTransition(
            ResearchChatState.Failed(target),
            ResearchChatIntent.Public.Retry,
            ResearchChatState.Loading(target),
            effects = listOf(ResearchChatEffect.Load(target)),
        )
    }

    @Test
    fun `submission reserves question until native completion and only acceptance clears draft`() {
        val submitting = ready.copy(submitting = setOf(question.id))
        ResearchChatMachineSpec.assertTransition(
            ready,
            ResearchChatIntent.Public.Submit("  question  "),
            submitting,
            effects = listOf(ResearchChatEffect.Run(session.id, question.id, "question")),
        )
        ResearchChatMachineSpec.assertIgnored(submitting, ResearchChatIntent.Public.Submit("again"))
        ResearchChatMachineSpec.assertTransition(
            submitting,
            ResearchChatIntent.Internal.Submitted(question.id),
            submitting,
            outputs = listOf(ResearchChatOutput.Submitted(question.id)),
        )
        ResearchChatMachineSpec.assertTransition(
            submitting,
            ResearchChatIntent.Internal.RunFinished(question.id, true),
            ready.copy(hasError = true),
        )
    }

    @Test
    fun `blank input and disabled workspace reject new work but permit stopping active turn`() {
        ResearchChatMachineSpec.assertIgnored(ready, ResearchChatIntent.Public.Submit("  "))
        ResearchChatMachineSpec.assertIgnored(ready.copy(isEnabled = false), ResearchChatIntent.Public.NewSession)
        val disabledRunning = ready.copy(isEnabled = false, workspace = workspace.copy(running = setOf(question.id)))
        ResearchChatMachineSpec.assertIgnored(disabledRunning, ResearchChatIntent.Public.Submit("question"))
        ResearchChatMachineSpec.assertTransition(
            disabledRunning,
            ResearchChatIntent.Public.Stop,
            disabledRunning,
            effects = listOf(ResearchChatEffect.Stop(question.id)),
        )
    }

    @Test
    fun `question selection isolates drafts and ignores foreign questions`() {
        val next = ResearchQuestion("q2")
        val state = ready.copy(workspace = ResearchWorkspace(listOf(session.copy(questions = listOf(question, next)))))
        ResearchChatMachineSpec.assertTransition(
            state,
            ResearchChatIntent.Public.SelectQuestion(next.id),
            state.copy(questionId = next.id),
        )
        ResearchChatMachineSpec.assertIgnored(state, ResearchChatIntent.Public.SelectQuestion("foreign"))
    }

    @Test
    fun `new question and session serialize mutations and failures release controls`() {
        ResearchChatMachineSpec.assertTransition(
            ready,
            ResearchChatIntent.Public.NewQuestion,
            ready.copy(isMutating = true),
            effects = listOf(ResearchChatEffect.CreateQuestion(session.id)),
        )
        ResearchChatMachineSpec.assertTransition(
            ready,
            ResearchChatIntent.Public.NewSession,
            ready.copy(isMutating = true),
            effects = listOf(ResearchChatEffect.CreateSession(target)),
        )
        ResearchChatMachineSpec.assertIgnored(ready.copy(isMutating = true), ResearchChatIntent.Public.NewQuestion)
        ResearchChatMachineSpec.assertTransition(
            ready.copy(isMutating = true),
            ResearchChatIntent.Internal.MutationFailed,
            ready.copy(hasError = true),
        )
    }

    @Test
    fun `resource input clears on success only and cannot change during generation`() {
        val input = ResearchChatIntent.Public.AddResource(
            ResearchResourceKind.Website,
            "Title",
            "https://example.com",
            ResearchResourceScope.Question,
        )
        ResearchChatMachineSpec.assertTransition(
            ready,
            input,
            ready.copy(isMutating = true),
            effects = listOf(ResearchChatEffect.AddResource(session.id, question.id, input)),
        )
        ResearchChatMachineSpec.assertIgnored(ready.copy(submitting = setOf(question.id)), input)
        ResearchChatMachineSpec.assertTransition(
            ready.copy(isMutating = true),
            ResearchChatIntent.Internal.ResourceAdded,
            ready,
            outputs = listOf(ResearchChatOutput.ResourceAdded),
        )
    }

    @Test
    fun `run effect failure releases only its own question reservation`() {
        val effect = ResearchChatEffect.Run(session.id, question.id, "question")
        assertEquals(
            ResearchChatIntent.Internal.RunFinished(question.id, true),
            ResearchChatMachineSpec.onEffectFailure(effect, IllegalStateException("test")),
        )
    }
}
