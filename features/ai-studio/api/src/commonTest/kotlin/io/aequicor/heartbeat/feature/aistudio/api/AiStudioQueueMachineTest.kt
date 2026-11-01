package io.aequicor.heartbeat.feature.aistudio.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import kotlin.test.Test
import kotlin.test.assertEquals

class AiStudioQueueMachineTest {
    private val settings = RunSettings("model", ReasoningEffort.High, ApprovalMode.Ask)
    private val files = listOf(ResourceRef("attachment:image", "image/png"))
    private val ready = AiStudioState.Ready(
        listOf(StudioPane(0, sessionId = "chat")),
        0,
        settings,
        isMessageQueueEnabled = true,
    )
    private val message = StudioQueuedMessage("queued", "Waiting", emptyList(), settings)
    private val queues = StudioMessageQueues(mapOf("chat" to StudioChatQueue(listOf(message))))

    @Test
    fun `submitting during execution queues the exact captured correction and host environment`() {
        val running = ready.copy(running = setOf("chat"))
        val environment = object : StudioChatEnvironment {
            override fun bind(session: SessionRef): AutoCloseable = error("Not used by the machine")
        }
        val submit = AiStudioIntent.Public.Submit(
            0,
            "  Correct this  ",
            files,
            "capture",
            environment = environment,
            userInstruction = "Original human instruction",
            isCorrection = true,
        )
        val effect = AiStudioEffect.QueueMessage(
            0,
            "chat",
            StudioQueuedMessage("capture", "Correct this", files, settings, "Original human instruction", true),
            environment,
            true,
        )
        AiStudioMachineSpec.assertTransition(running, submit, running, effects = listOf(effect))
        AiStudioMachineSpec.assertIgnored(running, submit.copy(prompt = " ", attachments = emptyList()))
        AiStudioMachineSpec.assertIgnored(running.copy(isMessageQueueEnabled = false), submit)
        assertEquals(
            AiStudioIntent.Internal.QueueRejected(0, "capture", "chat"),
            AiStudioMachineSpec.onEffectFailure(effect, IllegalStateException("Queue unavailable")),
        )
    }

    @Test
    fun `idle submission joins existing human input instead of overtaking the queue`() {
        val waiting = ready.copy(messageQueues = queues)
        AiStudioMachineSpec.assertTransition(
            waiting,
            AiStudioIntent.Public.Submit(0, "Next", submissionId = "next"),
            waiting,
            effects = listOf(
                AiStudioEffect.QueueMessage(
                    0,
                    "chat",
                    StudioQueuedMessage("next", "Next", emptyList(), settings),
                    null,
                    false,
                ),
            ),
        )
    }

    @Test
    fun `queue management requires an existing queue while notices remain independently dismissible`() {
        val waiting = ready.copy(messageQueues = queues)
        val remove = AiStudioIntent.Public.ManageQueue("chat", StudioQueueAction.Remove, "queued")
        val effect = AiStudioEffect.ManageQueue("chat", StudioQueueAction.Remove, "queued")
        AiStudioMachineSpec.assertTransition(waiting, remove, waiting, effects = listOf(effect))
        AiStudioMachineSpec.assertIgnored(ready, remove)
        AiStudioMachineSpec.assertTransition(
            ready,
            AiStudioIntent.Public.ManageQueue("chat", StudioQueueAction.DismissNotice, "notice"),
            ready,
            effects = listOf(AiStudioEffect.ManageQueue("chat", StudioQueueAction.DismissNotice, "notice")),
        )
        assertEquals(
            AiStudioIntent.Internal.QueueActionFailed("chat"),
            AiStudioMachineSpec.onEffectFailure(effect, IllegalStateException("Queue unavailable")),
        )
    }

    @Test
    fun `durable queue admission and action refusal emit correlated outputs without changing execution`() {
        val running = ready.copy(running = setOf("chat"), messageQueues = queues)
        AiStudioMachineSpec.assertTransition(
            running,
            AiStudioIntent.Internal.QueueAccepted(0, "capture", "chat"),
            running,
            outputs = listOf(AiStudioOutput.SubmitQueued(0, "capture", "chat")),
        )
        AiStudioMachineSpec.assertTransition(
            running,
            AiStudioIntent.Internal.QueueActionFailed("chat"),
            running,
            outputs = listOf(AiStudioOutput.QueueActionFailed("chat")),
        )
    }
}
