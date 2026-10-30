package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionAnswer
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionChoice
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionInput
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestsPermissions
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermission
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionAnswer
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionInput
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionOption
import io.aequicor.heartbeat.feature.aistudio.api.StudioRuntimeState
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioPermissionAnswerOrigin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Projects a pending facade request of [sessionId] into the studio machine's permission. */
internal fun PermissionRequest.toStudio(sessionId: String) = StudioPermission(
    sessionId,
    id.value,
    title,
    options.map { StudioPermissionOption(it.id.value, it.title, it.isSkip) },
    description = description,
    turnId = turn.value,
    input = when (val input = input) {
        null -> null

        is PermissionInput.SingleChoice -> StudioPermissionInput.SingleChoice(
            input.choices.toStudio(),
            input.isFreeTextAllowed,
        )

        is PermissionInput.MultiChoice ->
            StudioPermissionInput.MultiChoice(input.choices.toStudio(), input.min, input.max)

        is PermissionInput.FreeText -> StudioPermissionInput.FreeText(input.placeholder, input.isMultiline)
    },
)

/** The facade answer of a studio answer. */
internal fun StudioPermissionAnswer.toFacade(): PermissionAnswer = when (this) {
    is StudioPermissionAnswer.Selected -> PermissionAnswer.Selected(ids)
    is StudioPermissionAnswer.Text -> PermissionAnswer.Text(value)
}

private fun List<PermissionChoice>.toStudio() = map { StudioPermissionOption(it.id, it.title) }

/** Sends an exact pending option of this handle with its structured [answer]; a stale answer fails loudly. */
internal suspend fun ActiveSession?.respondStudioPermission(
    requestId: String,
    optionId: String,
    answer: StudioPermissionAnswer?,
    expectedTurnId: String? = null,
    expectedPermission: StudioPermission? = null,
    origin: StudioPermissionAnswerOrigin = StudioPermissionAnswerOrigin.ManualOrUnknown,
    beforeAnswer: suspend () -> Unit = { error("Human authority revocation is unavailable") },
) {
    // Native input protocols erase answer provenance and may interpret even structured text as an approval.
    check(origin !is StudioPermissionAnswerOrigin.NativeAutomatic) {
        "Native automatic answers require protocol provenance support"
    }
    Log.tag("EngineStudio").i { "Delivering native permission" }
    val active = this ?: rejectPermission("no open native session", requestId, optionId)
    val pending = active.state.value as? ActiveSessionState.AwaitingUserAction
        ?: rejectPermission("the session no longer awaits a decision", requestId, optionId)
    requirePermissionTurn(pending, expectedTurnId)
    val request = pending.requests.firstOrNull { it.id.value == requestId }
        ?: rejectPermission("the request is no longer pending", requestId, optionId)
    check(expectedPermission == null || request.toStudio(expectedPermission.sessionId) == expectedPermission) {
        "Permission changed before delivery"
    }
    val option = request.options.firstOrNull { it.id.value == optionId }
        ?: rejectPermission("the option is not offered", requestId, optionId)
    val decision = PermissionDecision(request.turn, request.id, option.id, answer?.toFacade())
    currentCoroutineContext().ensureActive()
    requirePendingPermission(active, request)
    if (!option.isSkip && (answer != null || request.input != null)) beforeAnswer()
    currentCoroutineContext().ensureActive()
    requirePendingPermission(active, request)
    deliverStudioPermission(active.state, decision) {
        active.features.requireFeature(RequestsPermissions).respond(decision)
    }
}

/** Rechecks the full captured request around suspending validation and revocation, including reused identities. */
private fun requirePendingPermission(active: ActiveSession, request: PermissionRequest) {
    val pending = active.state.value as? ActiveSessionState.AwaitingUserAction
    check(pending?.turn?.id == request.turn && pending.requests.any { it == request }) {
        "Permission changed during authority revocation"
    }
}

/** Fails the answer so the machine shows the request again instead of hiding it forever. */
private fun rejectPermission(reason: String, requestId: String, optionId: String): Nothing {
    Log.tag("EngineStudio").w { "Permission answer rejected: $reason requestId=$requestId optionId=$optionId" }
    error("Permission answer rejected: $reason")
}

/** Unavailable is not a resolution. Retain only requests of that exact turn until native reconciliation. */
internal fun projectStudioPermissions(
    sessionId: String,
    state: ActiveSessionState,
    previous: List<StudioPermission>,
): List<StudioPermission> = when (state) {
    is ActiveSessionState.AwaitingUserAction -> state.requests.map { it.toStudio(sessionId) }

    is ActiveSessionState.Unavailable -> state.activeTurn?.let { turn ->
        previous.filter {
            it.sessionId == sessionId && it.turnId == turn.id.value &&
                turn.resolvedPermissions.none { resolved -> resolved.value == it.requestId }
        }
    }.orEmpty()

    is ActiveSessionState.Ready, is ActiveSessionState.Submitting, is ActiveSessionState.Running,
    is ActiveSessionState.Interrupting, is ActiveSessionState.Closing, ActiveSessionState.Closed,
    -> emptyList()
}

/** Checks the captured identity at the native command boundary, before looking up an option or invoking IO. */
internal fun requirePermissionTurn(state: ActiveSessionState.AwaitingUserAction, expectedTurnId: String?) {
    require(expectedTurnId == null || state.turn.id.value == expectedTurnId) { "Permission belongs to another turn" }
}

/** A captured permission must carry a turn even if its request id is later reused by the engine. */
internal suspend fun ActiveSession?.respondStudioPermission(
    permission: StudioPermission,
    optionId: String,
    answer: StudioPermissionAnswer?,
    origin: StudioPermissionAnswerOrigin = StudioPermissionAnswerOrigin.ManualOrUnknown,
    beforeAnswer: suspend () -> Unit = { error("Human authority revocation is unavailable") },
) {
    val turn = requireNotNull(permission.turnId) { "Native permission has no turn identity" }
    respondStudioPermission(
        permission.requestId,
        optionId,
        answer,
        expectedTurnId = turn,
        expectedPermission = permission,
        origin = origin,
        beforeAnswer = beforeAnswer,
    )
}

/** Projects one native handle without losing another session's pending actions or stop state. */
internal fun StudioRuntimeState.withPermissionState(
    id: String,
    state: ActiveSessionState,
    isStopRequested: Boolean,
    isStopSupported: Boolean,
): StudioRuntimeState {
    val isStopFailed = isStopRequested && state is ActiveSessionState.Unavailable && state.activeTurn != null
    return copy(
        permissions = permissions.filterNot { it.sessionId == id } + projectStudioPermissions(id, state, permissions),
        stopFailures = if (isStopFailed) stopFailures + id else stopFailures,
        uncancellable = if (isStopSupported) uncancellable - id else uncancellable + id,
    )
}
