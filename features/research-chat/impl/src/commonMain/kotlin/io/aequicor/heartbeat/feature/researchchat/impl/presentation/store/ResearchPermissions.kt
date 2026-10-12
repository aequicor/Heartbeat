package io.aequicor.heartbeat.feature.researchchat.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionAnswer
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionInput
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import kotlinx.collections.immutable.toImmutableList

internal fun ResearchPermissionUi.decision(
    option: ResearchPermissionOptionUi,
    selected: Set<String>,
    text: String,
): ResearchScreenIntent.DecidePermission {
    val answer =
        if (option.isSkip) {
            null
        } else {
            when (kind) {
                ResearchPermissionKind.Buttons -> null

                ResearchPermissionKind.SingleChoice,
                ResearchPermissionKind.MultiChoice,
                -> PermissionAnswer.Selected(selected.toList())

                ResearchPermissionKind.FreeText -> PermissionAnswer.Text(text)
            }
        }
    return ResearchScreenIntent.DecidePermission(id, option.id, answer)
}

internal fun ResearchPermissionUi.canSubmit(option: ResearchPermissionOptionUi, selected: Set<String>): Boolean =
    option.isSkip ||
        when (kind) {
            ResearchPermissionKind.SingleChoice,
            ResearchPermissionKind.MultiChoice,
            -> selected.size in min..max

            ResearchPermissionKind.Buttons,
            ResearchPermissionKind.FreeText,
            -> true
        }

internal fun ResearchPermissionUi.changeSelection(selected: Set<String>, id: String, checked: Boolean): Set<String> =
    when {
        !checked -> selected - id
        kind == ResearchPermissionKind.SingleChoice -> setOf(id)
        else -> selected + id
    }

internal fun PermissionRequest.toPermissionUi(): ResearchPermissionUi {
    val base =
        ResearchPermissionUi(
            id.value,
            title,
            description,
            options
                .map { ResearchPermissionOptionUi(it.id.value, it.title, it.isSkip) }
                .toImmutableList(),
        )
    return when (val input = input) {
        null -> base

        is PermissionInput.SingleChoice ->
            base.copy(
                kind = ResearchPermissionKind.SingleChoice,
                choices = input.choices.map { it.id to it.title }.toImmutableList(),
                min = 1,
                max = 1,
            )

        is PermissionInput.MultiChoice ->
            base.copy(
                kind = ResearchPermissionKind.MultiChoice,
                choices = input.choices.map { it.id to it.title }.toImmutableList(),
                min = input.min,
                max = input.max,
            )

        is PermissionInput.FreeText ->
            base.copy(
                kind = ResearchPermissionKind.FreeText,
                placeholder = input.placeholder,
                isMultiline = input.isMultiline,
            )
    }
}
