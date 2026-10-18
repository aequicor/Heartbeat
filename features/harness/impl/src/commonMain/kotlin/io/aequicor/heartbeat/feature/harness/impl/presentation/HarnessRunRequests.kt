package io.aequicor.heartbeat.feature.harness.impl.presentation

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionAnswer
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionChoice
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionInput
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismPermission
import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/** How a cell wants its request answered: by the offered buttons alone, or with a choice or a text first. */
internal enum class RequestKindUi { Buttons, SingleChoice, MultiChoice, FreeText }

/** One action offered for a request; a skip declines the question without an answer. */
@Immutable
internal data class RequestOptionUi(val id: String, val title: String, val isSkip: Boolean)

/** One value of a choice request. */
@Immutable
internal data class RequestChoiceUi(val id: String, val title: String)

/**
 * A permission request of a cell of an organism that a workflow run owns. The organism has no chat, so the run is
 * where the user sees and answers it. [run], [step], [cell], [turn] and [request] address the exact request; none of
 * the texts is rendered by [toString].
 */
@Immutable
internal data class RunRequestUi(
    val run: String,
    val step: String,
    val cell: String,
    val cellName: String,
    val turn: String,
    val request: String,
    val title: String,
    val description: String?,
    val options: ImmutableList<RequestOptionUi>,
    val kind: RequestKindUi = RequestKindUi.Buttons,
    val choices: ImmutableList<RequestChoiceUi> = persistentListOf(),
    val min: Int = 0,
    val max: Int = 0,
    val placeholder: String? = null,
    val isMultiline: Boolean = false,
) {
    /** Stable identity in lists; the turn is part of it because an engine may reuse a request id in a later turn. */
    val key: String get() = "$step/$cell/$turn/$request"

    override fun toString(): String = "RunRequestUi(run=$run, step=$step, ***)"
}

internal fun OrganismPermission.toUi(run: RunId): RunRequestUi {
    val base = RunRequestUi(
        run = run.value,
        step = step.value,
        cell = cell,
        cellName = cellName,
        turn = request.turn.value,
        request = request.id.value,
        title = request.title,
        description = request.description,
        options = request.options.map { RequestOptionUi(it.id.value, it.title, it.isSkip) }.toImmutableList(),
    )
    return when (val input = request.input) {
        null -> base

        is PermissionInput.SingleChoice -> base.copy(
            kind = RequestKindUi.SingleChoice,
            choices = input.choices.toUi(),
            min = 1,
            max = 1,
        )

        is PermissionInput.MultiChoice -> base.copy(
            kind = RequestKindUi.MultiChoice,
            choices = input.choices.toUi(),
            min = input.min,
            max = input.max,
        )

        is PermissionInput.FreeText -> base.copy(
            kind = RequestKindUi.FreeText,
            placeholder = input.placeholder,
            isMultiline = input.isMultiline,
        )
    }
}

private fun List<PermissionChoice>.toUi() = map { RequestChoiceUi(it.id, it.title) }.toImmutableList()

/** Whether the offered [option] can be sent with this input: a skip always, any other once its input is complete. */
internal fun RunRequestUi.canSubmit(option: RequestOptionUi, selected: Set<String>): Boolean = option.isSkip ||
    when (kind) {
        RequestKindUi.SingleChoice, RequestKindUi.MultiChoice -> selected.size in min..max
        RequestKindUi.Buttons, RequestKindUi.FreeText -> true
    }

/** Toggles [id]: a single choice selects it, a multiple choice adds or removes it. */
internal fun RunRequestUi.changeSelection(selected: Set<String>, id: String, isChecked: Boolean): Set<String> = when {
    !isChecked -> selected - id
    kind == RequestKindUi.SingleChoice -> setOf(id)
    else -> selected + id
}

/**
 * The decision for the offered option [optionId] with the input the user gave, or null when no such option is
 * offered or its input is incomplete. A skip carries no answer; a request without input takes none.
 */
internal fun RunRequestUi.decision(optionId: String, selected: Set<String>, text: String): PermissionDecision? {
    val option = options.firstOrNull { it.id == optionId }?.takeIf { canSubmit(it, selected) } ?: return null
    val answer = when {
        option.isSkip -> null

        kind == RequestKindUi.SingleChoice || kind == RequestKindUi.MultiChoice ->
            PermissionAnswer.Selected(choices.map { it.id }.filter { it in selected })

        kind == RequestKindUi.FreeText -> PermissionAnswer.Text(text)

        else -> null
    }
    return PermissionDecision(TurnId(turn), PermissionRequestId(request), PermissionOptionId(option.id), answer)
}
