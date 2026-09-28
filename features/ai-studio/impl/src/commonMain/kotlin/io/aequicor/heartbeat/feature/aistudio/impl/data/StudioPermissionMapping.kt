package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionAnswer
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionChoice
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionInput
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
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
