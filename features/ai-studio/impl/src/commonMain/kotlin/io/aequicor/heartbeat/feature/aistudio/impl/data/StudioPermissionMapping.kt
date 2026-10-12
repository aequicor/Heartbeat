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

/** Projects a pending facade request of [sessionId] into the studio machine's permission. */
internal fun PermissionRequest.toStudio(sessionId: String) = StudioPermission(
    sessionId,
    id.value,
    title,
    options.map { StudioPermissionOption(it.id.value, it.title, it.isSkip) },
    description = description,
    input = when (val input = input) {
        null -> null

        is PermissionInput.SingleChoice -> StudioPermissionInput.SingleChoice(input.choices.toStudio())

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
) {
    val active = this ?: rejectPermission("no open native session", requestId, optionId)
    val pending = active.state.value as? ActiveSessionState.AwaitingUserAction
        ?: rejectPermission("the session no longer awaits a decision", requestId, optionId)
    val request = pending.requests.firstOrNull { it.id.value == requestId }
        ?: rejectPermission("the request is no longer pending", requestId, optionId)
    val option = request.options.firstOrNull { it.id.value == optionId }
        ?: rejectPermission("the option is not offered", requestId, optionId)
    active.features.requireFeature(RequestsPermissions)
        .respond(PermissionDecision(request.turn, request.id, option.id, answer?.toFacade()))
}

/** Fails the answer so the machine shows the request again instead of hiding it forever. */
private fun rejectPermission(reason: String, requestId: String, optionId: String): Nothing {
    Log.tag("EngineStudio").w { "Permission answer rejected: $reason requestId=$requestId optionId=$optionId" }
    error("Permission answer rejected: $reason")
}
