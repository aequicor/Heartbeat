package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReconcilesSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SteeringRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SteersTurns
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatEnvironment
import io.aequicor.heartbeat.feature.aistudio.api.StudioQueueAction
import io.aequicor.heartbeat.feature.aistudio.api.StudioQueuedMessage
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunKind
import kotlinx.coroutines.CancellationException

/** Capture once: an eventual delivery cannot drift into the next native turn. */
internal fun studioSteeringTarget(active: ActiveSession, chat: String): StudioSteeringTarget {
    active.features.requireFeature(SteersTurns)
    val turn = when (val current = active.state.value) {
        is ActiveSessionState.Running -> current.turn
        is ActiveSessionState.AwaitingUserAction -> current.turn
        else -> error("The native turn is not steerable")
    }
    return StudioSteeringTarget(active, turn.id, chat)
}

/** Does not bind a new turn or replace its trust policy. */
internal suspend fun StudioSteeringTarget.deliver(message: StudioQueuedMessage) {
    val parts = listOfNotNull(message.text.takeIf { it.isNotBlank() }?.let(ContentPart::Text)) +
        message.attachments.map {
            if (it.mediaType.startsWith(
                    "image/",
                )
            ) {
                ContentPart.Image(it)
            } else {
                ContentPart.Resource(it)
            }
        }
    session.features.requireFeature(SteersTurns).steer(turn, SteeringRequest(RequestId(message.id), parts))
}

/** Stop and failure barriers belong to the profile even if the screen disappears. */
internal suspend fun settleStudioQueue(queue: StudioQueuePort, request: StudioTurnRequest, outcome: RunOutcome) {
    try {
        queue.settled(request.id, request.request.value, outcome, request.submission?.hasBegun != false)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.tag("StudioMessageQueue").e(error) { "Could not settle queued input; retain native ownership" }
    }
}

/** Editing a row is independent of execution ownership and never changes a native turn by itself. */
internal suspend fun StudioQueuePort.manage(chat: String, action: StudioQueueAction, message: String) {
    try {
        when (action) {
            StudioQueueAction.Correct -> correct(chat, message)
            StudioQueueAction.Remove -> remove(chat, message)
            StudioQueueAction.Resume -> resume(chat)
            StudioQueueAction.DismissNotice -> dismiss(chat, message)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.tag("StudioMessageQueue").w(error) { "Queue edit refused; preserve captured messages" }
        reportFailure(chat)
    }
}

/** Native execution boundary is separate from profile queue persistence to avoid repository lifetime cycles. */
@Inject
@ContributesBinding(ProfileScope::class)
internal class StudioQueueDelivery(
    private val repository: Lazy<EngineStudioRepository>,
    private val runs: StudioRunCoordinator,
) : StudioQueueHost {
    override suspend fun queuedRun(
        chat: String,
        message: StudioQueuedMessage,
        environment: StudioChatEnvironment?,
        submission: StudioSubmissionGate,
        accepted: suspend () -> Unit,
    ) {
        repository.value.validateQueuedInput(chat, environment)
        runs.run(
            repository.value,
            studioTurnRequest(
                chat, message.text, message.settings, WorktreeRunKind.Coding, RequestId(message.id),
                attachments = message.attachments, onAccepted = accepted, environment = environment,
                isUserPrompt = true, userInstruction = message.userInstruction,
            ).copy(submission = submission),
            waitForIdle = true,
        )
    }

    override suspend fun steeringTarget(chat: String): StudioSteeringTarget = repository.value.steeringTarget(chat)

    override suspend fun steerQueued(target: StudioSteeringTarget, message: StudioQueuedMessage) {
        check(!message.hasEnvironment) { "Steering cannot replace a captured host environment" }
        repository.value.captureUserInstructions(target.chat, RequestId(message.id), message.userInstruction)
        target.deliver(message)
    }
}

/** Persisting a queue pause may fail; native Stop must still be delivered. */
internal suspend fun StudioQueuePort.pauseForStop(chat: String) {
    try {
        pause(chat)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.tag("StudioMessageQueue").e(error) { "Could not persist queue pause; stop retains priority" }
    }
}

/** Reconciles an unavailable lease before cancelling its exact current turn. */
internal suspend fun cancelStudioNativeTurn(active: ActiveSession, worktrees: StudioWorktrees) {
    if (active.state.value is ActiveSessionState.Unavailable) {
        active.features.requireFeature(ReconcilesSession).synchronize()
    }
    val turn = active.state.value.activeTurn() ?: return
    turn.request?.let { worktrees.cancelBuilds(active.ref, it, turn.id) }
    active.features.requireFeature(CancelsTurns).cancel(turn.id)
}
