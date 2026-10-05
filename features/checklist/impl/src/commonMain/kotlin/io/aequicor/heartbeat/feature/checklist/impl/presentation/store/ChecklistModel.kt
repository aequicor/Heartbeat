package io.aequicor.heartbeat.feature.checklist.impl.presentation.store

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.flowmvi.reflect
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.checklist.api.ChecklistAnswer
import io.aequicor.heartbeat.feature.checklist.api.ChecklistDelivery
import io.aequicor.heartbeat.feature.checklist.api.ChecklistInput
import io.aequicor.heartbeat.feature.checklist.api.ChecklistIntent
import io.aequicor.heartbeat.feature.checklist.api.ChecklistRoute
import io.aequicor.heartbeat.feature.checklist.api.ChecklistState
import io.aequicor.heartbeat.feature.checklist.api.ChecklistStatus
import io.aequicor.heartbeat.feature.checklist.impl.di.scope.ChecklistScope
import io.aequicor.heartbeat.feature.checklist.impl.domain.ChecklistAccess
import io.aequicor.heartbeat.feature.checklist.impl.domain.ChecklistMachine
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import pro.respawn.flowmvi.plugins.reduce

/** Reflects the profile machine; every answer is persisted and validated by that machine. */
@SingleIn(ChecklistScope::class)
@Inject
internal class ChecklistModel(
    machine: ChecklistMachine,
    route: ChecklistRoute,
    access: ChecklistAccess,
    @ForScope(ChecklistScope::class) scope: ScopeHandle,
    factory: HeartbeatStoreFactory,
) {
    private val log = Log.tag("ChecklistScreen")
    val store = factory.create<ChecklistScreenState, ChecklistScreenIntent, ChecklistScreenAction>(
        name = "Checklist",
        initial = machine.state.value.toUi(route.id),
        onError = { copy(hasFailed = true) },
    ) {
        reflect(machine) { it.toUi(route.id) }
        reduce { input ->
            if (!access.isEnabled()) return@reduce
            val card = (machine.state.value as? ChecklistState.Ready)?.journal?.cards?.firstOrNull { it.id == route.id }
            val command = when (input) {
                is ChecklistScreenIntent.Choice -> {
                    val field = card?.fields?.firstOrNull { it.id == input.field } ?: return@reduce
                    val selected = card.answers[input.field]?.selected.orEmpty()
                    val next = when {
                        field.type == ChecklistInput.SingleChoice -> setOf(input.choice)
                        input.choice in selected -> selected - input.choice
                        else -> selected + input.choice
                    }
                    ChecklistIntent.Public.Answer(route.id, input.field, ChecklistAnswer(selected = next))
                }

                is ChecklistScreenIntent.Text -> ChecklistIntent.Public.Answer(
                    route.id,
                    input.field,
                    ChecklistAnswer(text = input.text),
                )

                ChecklistScreenIntent.Complete -> ChecklistIntent.Public.Complete(route.id)

                ChecklistScreenIntent.Retry -> ChecklistIntent.Public.Retry

                ChecklistScreenIntent.RetryDelivery -> ChecklistIntent.Public.RetryDelivery(route.id)
            }
            sendTo(machine, command) { log.w { "Checklist input was not accepted: $it" } }
        }
    }
    init {
        store.start(scope.coroutineScope)
    }
}

internal fun ChecklistState.toUi(id: String): ChecklistScreenState = when (this) {
    is ChecklistState.Loading -> ChecklistScreenState(hasFailed = hasFailed)
    is ChecklistState.Ready -> toScreenState(id)
}

private fun ChecklistState.Ready.toScreenState(id: String): ChecklistScreenState {
    val card = journal.cards.firstOrNull { it.id == id } ?: return ChecklistScreenState()
    return ChecklistScreenState(
        title = card.title,
        fields = card.fields.map { field ->
            val answer = card.answers[field.id] ?: ChecklistAnswer()
            ChecklistFieldUi(
                field.id, field.title, field.type == ChecklistInput.Text, field.type == ChecklistInput.MultiChoice,
                field.isRequired, field.minimumSelections, field.max,
                field.choices.map { ChecklistChoiceUi(it.id, it.title) }.toImmutableList(),
                answer.selected.toImmutableSet(), answer.text,
            )
        }.toImmutableList(),
        phase = when (card.status) {
            ChecklistStatus.Open -> ChecklistPhaseUi.Open
            ChecklistStatus.Completed -> ChecklistPhaseUi.Completed
            ChecklistStatus.Superseded -> ChecklistPhaseUi.Superseded
        },
        isAutomatic = card.isAutomatic,
        isCompletionAllowed = card.isCompletionAllowed,
        isSaving = savedRevision < journal.revision,
        hasFailed = hasFailed,
        isDeliveryFailed = card.delivery == ChecklistDelivery.Failed,
        isDeliveryPending = card.delivery == ChecklistDelivery.Pending,
    )
}
