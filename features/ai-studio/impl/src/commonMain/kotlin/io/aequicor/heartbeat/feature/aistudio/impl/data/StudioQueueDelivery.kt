package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReconcilesSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SteeringRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SteersTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatEnvironment
import io.aequicor.heartbeat.feature.aistudio.api.StudioQueueAction
import io.aequicor.heartbeat.feature.aistudio.api.StudioQueuedMessage
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** Capture once: an eventual delivery cannot drift into the next native turn. */
internal fun studioSteeringTarget(active: ActiveSession, chat: String): StudioSteeringTarget {
    val isNative = active.features.resolve(SteersTurns) is FeatureAccess.Available
    if (!isNative) active.features.requireFeature(CancelsTurns)
    val turn = when (val current = active.state.value) {
        is ActiveSessionState.Running -> current.turn
        is ActiveSessionState.AwaitingUserAction -> current.turn
        else -> error("The native turn is not steerable")
    }
    return StudioSteeringTarget(active, turn.id, chat, isNative, turn.request?.value)
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
    private val chats: Lazy<StudioQueueAccess>,
    private val host: Lazy<StudioRunHost>,
    private val authority: Lazy<StudioConfigurationAccess>,
    private val runs: StudioRunCoordinator,
    private val worktrees: StudioWorktrees,
) : StudioQueueHost {
    override suspend fun queuedRun(
        chat: String,
        message: StudioQueuedMessage,
        environment: StudioChatEnvironment?,
        submission: StudioSubmissionGate,
        accepted: suspend () -> Unit,
    ) {
        chats.value.validateQueuedInput(chat, environment)
        runs.run(
            host.value,
            studioTurnRequest(
                chat, message.text, message.settings, WorktreeRunKind.Coding, RequestId(message.id),
                attachments = message.attachments, onAccepted = accepted, environment = environment,
                isUserPrompt = true, userInstruction = message.userInstruction,
            ).copy(submission = submission),
            waitForIdle = true,
        )
    }

    override suspend fun interrupt(target: StudioSteeringTarget) {
        target.request?.let { worktrees.cancelBuilds(target.session.ref, RequestId(it), target.turn) }
        target.interrupt()
    }

    override suspend fun steeringTarget(chat: String): StudioSteeringTarget = chats.value.steeringTarget(chat)

    override suspend fun steerQueued(target: StudioSteeringTarget, message: StudioQueuedMessage) {
        check(!message.hasEnvironment) { "Steering cannot replace a captured host environment" }
        chats.value.validateQueuedInput(target.chat, null)
        authority.value.captureUserInstructions(target.chat, RequestId(message.id), message.userInstruction)
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
    // A second human Stop still revokes queued correction, but cancellation is already in flight.
    val turn = active.state.value.takeUnless { it is ActiveSessionState.Interrupting }?.activeTurn() ?: return
    turn.request?.let { worktrees.cancelBuilds(active.ref, it, turn.id) }
    fun alreadyStopping(): Boolean = when (val current = active.state.value) {
        is ActiveSessionState.Interrupting -> current.turn.id == turn.id
        is ActiveSessionState.Ready -> current.lastTurn?.id == turn.id
        else -> false
    }
    if (alreadyStopping()) return
    try {
        active.features.requireFeature(CancelsTurns).cancel(turn.id)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        if (!alreadyStopping()) throw error
        Log.tag("StudioMessageQueue").w(error) { "Captured turn was already stopping when Stop arrived" }
    }
}

/** Narrow profile boundary keeps Metro contribution providers independent of the concrete repository type. */
internal interface StudioQueueAccess {
    suspend fun validateQueuedInput(chat: String, environment: StudioChatEnvironment?)
    suspend fun steeringTarget(chat: String): StudioSteeringTarget
}

/** Only an authoritative terminal outcome of the captured turn permits a replacement prompt. */
internal suspend fun StudioSteeringTarget.interrupt() = interruptStudioTurn(session.state, turn) {
    session.features.requireFeature(CancelsTurns).cancel(turn)
}

/** A cancel RPC returning successfully is not proof that the captured turn ended. */
internal suspend fun interruptStudioTurn(
    state: StateFlow<ActiveSessionState>,
    turn: TurnId,
    cancel: suspend () -> Unit,
) {
    fun confirmed(current: ActiveSessionState): Boolean {
        val finished = (current as? ActiveSessionState.Ready)?.lastTurn
        return finished?.id == turn && finished.outcome in setOf(TurnOutcome.Completed, TurnOutcome.Cancelled)
    }
    withTimeout(CORRECTION_STOP_TIMEOUT_MS) {
        if (confirmed(state.value)) return@withTimeout
        try {
            cancel()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (!confirmed(state.value)) throw error
            Log.tag("StudioMessageQueue").w(error) { "Captured turn ended before cancellation was admitted" }
        }
        val terminal = state.first { current ->
            current !is ActiveSessionState.Running && current !is ActiveSessionState.AwaitingUserAction &&
                current !is ActiveSessionState.Interrupting && current !is ActiveSessionState.Submitting
        }
        check(confirmed(terminal)) { "Cancellation was not confirmed for the captured turn" }
    }
}

private const val CORRECTION_STOP_TIMEOUT_MS = 40_000L
